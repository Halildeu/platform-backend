package com.serban.notify.worker;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.adapter.ChannelAdapterRegistry;
import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.config.NotifyConfig;
import com.serban.notify.delivery.DeliveryPlanService;
import com.serban.notify.delivery.DeliveryTarget;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.domain.NotificationTemplate;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import com.serban.notify.repository.NotificationTemplateRepository;
import com.serban.notify.template.RenderedMessage;
import com.serban.notify.template.TemplateRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * RetryWorker — claim due RETRY deliveries + re-dispatch + DLQ exhausted
 * (Codex 019dfa47 Q1+Q3+Q5 absorb).
 *
 * <p>Cycle:
 * <ol>
 *   <li>Atomic native claim: RETRY deliveries with {@code next_retry_at &lt;= now}
 *       and lease expired/null → set new lease</li>
 *   <li>For each claimed delivery:
 *     <ul>
 *       <li>If {@code attempt_count >= max-attempts} → DeadLetterService.moveToDlq</li>
 *       <li>Else → adapter.send + UPSERT delivery (next_retry_at via BackoffCalculator)</li>
 *     </ul>
 *   </li>
 * </ol>
 *
 * <p>Activated only when {@code notify.dispatch.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "notify.dispatch.enabled", havingValue = "true")
public class RetryWorker {

    private static final Logger log = LoggerFactory.getLogger(RetryWorker.class);

    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationIntentRepository intentRepo;
    private final NotificationTemplateRepository templateRepo;
    private final ChannelAdapterRegistry adapterRegistry;
    private final TemplateRenderer renderer;
    private final BackoffCalculator backoffCalculator;
    private final DeadLetterService dlqService;
    private final DeliveryPlanService planService;
    private final AuditEventPublisher audit;
    private final WorkerMetrics metrics;
    private final NotifyConfig.WorkerConfig workerCfg;
    private final NotifyConfig.RetryConfig retryCfg;
    private final boolean schedulingEnabled;
    private RetryWorker self;  // self-injection for REQUIRES_NEW

    public RetryWorker(
        NotificationDeliveryRepository deliveryRepo,
        NotificationIntentRepository intentRepo,
        NotificationTemplateRepository templateRepo,
        ChannelAdapterRegistry adapterRegistry,
        TemplateRenderer renderer,
        BackoffCalculator backoffCalculator,
        DeadLetterService dlqService,
        DeliveryPlanService planService,
        AuditEventPublisher audit,
        WorkerMetrics metrics,
        NotifyConfig notifyConfig,
        @org.springframework.beans.factory.annotation.Value("${notify.worker.scheduling-enabled:true}")
            boolean schedulingEnabled
    ) {
        this.deliveryRepo = deliveryRepo;
        this.intentRepo = intentRepo;
        this.templateRepo = templateRepo;
        this.adapterRegistry = adapterRegistry;
        this.renderer = renderer;
        this.backoffCalculator = backoffCalculator;
        this.dlqService = dlqService;
        this.planService = planService;
        this.audit = audit;
        this.metrics = metrics;
        this.workerCfg = notifyConfig.worker();
        this.retryCfg = notifyConfig.retry();
        this.schedulingEnabled = schedulingEnabled;
        log.info("RetryWorker activated: batchSize={} maxAttempts={} pollDelay={}ms scheduling={}",
            workerCfg.retryBatchSize(), retryCfg.maxAttempts(),
            workerCfg.pollDelayMs(), schedulingEnabled);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy RetryWorker self) {
        this.self = self;
    }

    @Scheduled(fixedDelayString = "${notify.worker.poll-delay-ms:5000}")
    public void tick() {
        if (!schedulingEnabled) return;
        runCycle();
    }

    /** Public cycle entry — called by @Scheduled tick() OR directly by tests. */
    public void runCycle() {
        try {
            int claimed = claimAndProcess();
            metrics.cycle("retry", claimed > 0 ? "active" : "empty");
            if (claimed > 0) {
                log.info("RetryWorker cycle: claimed={}", claimed);
            }
        } catch (RuntimeException e) {
            log.warn("RetryWorker cycle error: {}", e.getMessage(), e);
            metrics.error("retry", "cycle");
            metrics.cycle("retry", "error");
        }
    }

    private int claimAndProcess() {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime leaseUntil = now.plus(Duration.ofMillis(workerCfg.leaseDurationMs()));
        String claimToken = java.util.UUID.randomUUID().toString();
        int claimed = self.claimAtomic(now, leaseUntil, claimToken);
        if (claimed == 0) return 0;
        metrics.claimed("retry", claimed);

        // Codex iter-1 P0 #2 absorb: fetch ONLY this cycle's claims via claim_token
        List<NotificationDelivery> claimedDeliveries = deliveryRepo.findByClaimToken(claimToken);
        for (NotificationDelivery delivery : claimedDeliveries) {
            try {
                self.processDelivery(delivery);
            } catch (RuntimeException e) {
                log.warn("retry delivery processing failed: id={}: {}",
                    delivery.getId(), e.getMessage(), e);
                metrics.error("retry", "process");
            }
        }
        return claimed;
    }

    @Transactional
    public int claimAtomic(OffsetDateTime now, OffsetDateTime leaseUntil, String claimToken) {
        return deliveryRepo.claimDueForRetry(now, leaseUntil, claimToken, workerCfg.retryBatchSize());
    }

    /**
     * Process single claimed RETRY delivery — REQUIRES_NEW.
     *
     * <p>Order:
     * <ol>
     *   <li>If {@code attempt_count >= max-attempts} → DLQ + return</li>
     *   <li>Resolve intent + template + render</li>
     *   <li>Adapter.send (RuntimeException → RETRY classify)</li>
     *   <li>UPSERT delivery aggregate: status, attempt_count++,
     *       next_retry_at via BackoffCalculator (RETRY), permanent_failure_at
     *       (FAILED/BOUNCED), delivered_at (DELIVERED)</li>
     *   <li>Clear lease</li>
     *   <li>Audit DELIVERY_ATTEMPTED + outcome event</li>
     *   <li>Trigger DLQ if exhausted on this attempt</li>
     * </ol>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void processDelivery(NotificationDelivery delivery) {
        // Reload to ensure fresh state (lease check)
        Optional<NotificationDelivery> latest = deliveryRepo.findById(delivery.getId());
        if (latest.isEmpty()) return;
        delivery = latest.get();

        // Pre-check: already exhausted?
        if (delivery.getAttemptCount() >= retryCfg.maxAttempts()) {
            dlqService.moveToDlq(delivery, "max_attempts");
            return;
        }

        NotificationIntent intent = intentRepo.findByIntentId(delivery.getIntentId()).orElse(null);
        if (intent == null) {
            log.warn("retry delivery {} has no intent (cleanup race?), terminating",
                delivery.getId());
            delivery.setStatus(NotificationDelivery.Status.FAILED);
            delivery.setFailureReason("intent missing");
            delivery.setPermanentFailureAt(OffsetDateTime.now());
            delivery.setProcessingLeaseUntil(null);
            deliveryRepo.save(delivery);
            return;
        }
        // Skip if intent terminal (race window)
        if (intent.getStatus() == NotificationIntent.Status.COMPLETED
            || intent.getStatus() == NotificationIntent.Status.FAILED
            || intent.getStatus() == NotificationIntent.Status.PARTIALLY_FAILED
            || intent.getStatus() == NotificationIntent.Status.EXPIRED) {
            log.info("retry skip — intent {} already terminal: {}",
                intent.getIntentId(), intent.getStatus());
            delivery.setProcessingLeaseUntil(null);
            deliveryRepo.save(delivery);
            return;
        }

        NotificationTemplate template = templateRepo.findByTemplateIdAndVersionAndLocale(
            intent.getTemplateId(), intent.getTemplateVersion(), intent.getLocale()
        ).orElse(null);
        if (template == null) {
            log.warn("retry delivery {} template missing — DLQ", delivery.getId());
            delivery.setFailureReason("template missing");
            dlqService.moveToDlq(delivery, "template_missing");
            return;
        }

        RenderedMessage message = renderer.render(template, intent.getPayload());

        ChannelAdapter adapter = adapterRegistry.get(delivery.getChannel()).orElse(null);
        if (adapter == null) {
            log.warn("retry delivery {} adapter missing for channel '{}' — DLQ",
                delivery.getId(), delivery.getChannel());
            delivery.setFailureReason("adapter missing for " + delivery.getChannel());
            dlqService.moveToDlq(delivery, "adapter_missing");
            return;
        }

        // Reconstruct DeliveryTarget — re-plan from intent (snapshot fallback +
        // channel routing) and find target with matching recipient_hash.
        DeliveryTarget target = findTargetForDelivery(intent, delivery);
        if (target == null) {
            log.warn("retry delivery {} target not found in re-plan — DLQ", delivery.getId());
            delivery.setFailureReason("target reconstruction failed");
            dlqService.moveToDlq(delivery, "target_reconstruction_failed");
            return;
        }

        // Pre-attempt audit
        audit.publish("DELIVERY_ATTEMPTED", intent, delivery.getRecipientHash(),
            delivery.getChannel(),
            Map.of("provider", delivery.getProvider(), "attempt", delivery.getAttemptCount() + 1));

        ChannelAdapter.DeliveryAttemptResult result;
        try {
            result = adapter.send(target, message);
        } catch (RuntimeException e) {
            log.warn("retry adapter exception (RETRY classify): channel={} err={}",
                delivery.getChannel(), e.getMessage());
            result = ChannelAdapter.DeliveryAttemptResult.retry(
                "exception: " + e.getClass().getSimpleName(), null);
        }

        // UPSERT delivery aggregate
        OffsetDateTime now = OffsetDateTime.now();
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setLastAttemptAt(now);
        delivery.setStatus(NotificationDelivery.Status.valueOf(result.status().name()));
        delivery.setProcessingLeaseUntil(null);

        if (result.status() == ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED) {
            delivery.setProviderMsgId(result.providerMessageId());
            delivery.setDeliveredAt(now);
            delivery.setFailureReason(null);
            delivery.setNextRetryAt(null);
        } else {
            delivery.setFailureReason(result.failureReason());
            if (result.status() == ChannelAdapter.DeliveryAttemptResult.Status.RETRY) {
                Duration delay = backoffCalculator.computeDelay(delivery.getAttemptCount());
                delivery.setNextRetryAt(now.plus(delay));
                metrics.retryScheduled(delivery.getChannel());
            } else {
                // FAILED / BOUNCED
                delivery.setPermanentFailureAt(now);
                delivery.setNextRetryAt(null);
            }
        }
        deliveryRepo.saveAndFlush(delivery);

        // Outcome audit
        Map<String, Object> details = new HashMap<>();
        details.put("delivery_status", result.status().name());
        details.put("attempt_count", delivery.getAttemptCount());
        if (result.providerMessageId() != null) details.put("provider_msg_id", result.providerMessageId());
        if (result.failureReason() != null) details.put("failure_reason", result.failureReason());
        if (result.providerResponseCode() != null) details.put("provider_response_code", result.providerResponseCode());
        switch (result.status()) {
            case DELIVERED -> audit.publish("DELIVERY_SUCCEEDED", intent,
                delivery.getRecipientHash(), delivery.getChannel(), details);
            case FAILED, BOUNCED -> audit.publish("DELIVERY_FAILED", intent,
                delivery.getRecipientHash(), delivery.getChannel(), details);
            case RETRY -> audit.publish("DELIVERY_ATTEMPTED", intent,
                delivery.getRecipientHash(), delivery.getChannel(), details);
        }

        metrics.dispatchOutcome(delivery.getChannel(), result.status().name());

        // Post-attempt: if exhausted on this attempt → DLQ
        if (delivery.getStatus() == NotificationDelivery.Status.RETRY
            && delivery.getAttemptCount() >= retryCfg.maxAttempts()) {
            dlqService.moveToDlq(delivery, "max_attempts");
        }
    }

    /**
     * Find DeliveryTarget for retry by re-planning intent + matching by
     * (channel, recipient_hash) — leverages PR3 plan() snapshot fallback.
     * Returns null if target no longer plannable (recipient removed, channel
     * routing changed, etc.) — caller terminates with DLQ.
     */
    private DeliveryTarget findTargetForDelivery(NotificationIntent intent, NotificationDelivery delivery) {
        try {
            List<DeliveryTarget> targets = planService.plan(intent, null);
            for (DeliveryTarget t : targets) {
                if (delivery.getChannel().equals(t.channel())
                    && delivery.getRecipientHash().equals(t.recipientHash())) {
                    return t;
                }
            }
            return null;
        } catch (RuntimeException e) {
            log.warn("plan re-construction failed for retry: intentId={} err={}",
                intent.getIntentId(), e.getMessage());
            return null;
        }
    }
}

package com.serban.notify.delivery;

import com.serban.notify.adapter.ChannelAdapter;
import com.serban.notify.adapter.ChannelAdapterRegistry;
import com.serban.notify.audit.AuditEventPublisher;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * DeliveryDispatchService — internal, direct-invoke (Codex 019df9ae Q1 REVISE absorb).
 *
 * <p>PR3 scope: tüm dispatch pipeline impl, AMA:
 * <ul>
 *   <li>Submit endpoint auto-dispatch çağırmaz (PR2 contract korunur)</li>
 *   <li>Scheduled worker yok (PR4)</li>
 *   <li>Test'lerde direct invoke; PR4 worker {@link #dispatchPlanned(NotificationIntent, List)}
 *       çağıracak</li>
 * </ul>
 *
 * <p>Pipeline:
 * <ol>
 *   <li>Resolve template body (DB lookup by template_id+version+locale snapshot
 *       on intent)</li>
 *   <li>TemplateRenderer.render → RenderedMessage</li>
 *   <li>For each DeliveryTarget: ChannelAdapter.send → DeliveryAttemptResult</li>
 *   <li>Persist delivery row + audit DELIVERY_SUCCEEDED/FAILED</li>
 *   <li>Update intent status (PROCESSING → COMPLETED if all delivered)</li>
 * </ol>
 */
@Service
public class DeliveryDispatchService {

    private static final Logger log = LoggerFactory.getLogger(DeliveryDispatchService.class);

    private final TemplateRenderer renderer;
    private final NotificationTemplateRepository templateRepo;
    private final NotificationIntentRepository intentRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final ChannelAdapterRegistry adapterRegistry;
    private final AuditEventPublisher audit;
    private DeliveryDispatchService self;  // Self-injection for REQUIRES_NEW boundary

    public DeliveryDispatchService(
        TemplateRenderer renderer,
        NotificationTemplateRepository templateRepo,
        NotificationIntentRepository intentRepo,
        NotificationDeliveryRepository deliveryRepo,
        ChannelAdapterRegistry adapterRegistry,
        AuditEventPublisher audit
    ) {
        this.renderer = renderer;
        this.templateRepo = templateRepo;
        this.intentRepo = intentRepo;
        this.deliveryRepo = deliveryRepo;
        this.adapterRegistry = adapterRegistry;
        this.audit = audit;
    }

    /**
     * Self-injection for {@code REQUIRES_NEW} per-target boundary (Codex
     * 019df9ef P2 absorb). Spring proxy {@code this.dispatchTarget(...)} direct
     * call bypasses transactional advice; via {@code self} bean reference the
     * proxy is invoked → REQUIRES_NEW takes effect per target.
     */
    @Autowired
    void setSelf(@Lazy DeliveryDispatchService self) {
        this.self = self;
    }

    /**
     * Dispatch planned targets for an intent.
     *
     * @param intent persisted NotificationIntent (status=PENDING)
     * @param targets DeliveryTargets from {@link DeliveryPlanService#plan}
     * @return number of targets attempted
     */
    @Transactional
    public int dispatchPlanned(NotificationIntent intent, List<DeliveryTarget> targets) {
        log.info("dispatch start: intentId={} target_count={}", intent.getIntentId(), targets.size());

        // Mark intent PROCESSING (idempotent — if already PROCESSING, retry path)
        if (intent.getStatus() == NotificationIntent.Status.PENDING) {
            intent.setStatus(NotificationIntent.Status.PROCESSING);
            intentRepo.save(intent);
        }

        NotificationTemplate template = templateRepo.findByTemplateIdAndVersionAndLocale(
            intent.getTemplateId(), intent.getTemplateVersion(), intent.getLocale()
        ).orElseThrow(() -> new IllegalStateException(
            "template missing at dispatch time: " + intent.getTemplateId()
                + " v" + intent.getTemplateVersion() + " " + intent.getLocale()
        ));

        RenderedMessage message = renderer.render(template, intent.getPayload());

        int attempted = 0;
        boolean anyFailedPermanent = false;
        boolean allDelivered = true;
        for (DeliveryTarget target : targets) {
            // Codex 019df9ef P2 absorb: per-target idempotent skip — if a
            // delivery row already DELIVERED for this (intent_id, channel,
            // recipient_hash) tuple, skip re-send. Replays/retries safe.
            Optional<NotificationDelivery> existing = deliveryRepo
                .findByIntentIdAndChannelAndRecipientHash(
                    intent.getIntentId(), target.channel(), target.recipientHash()
                );
            if (existing.isPresent() &&
                existing.get().getStatus() == NotificationDelivery.Status.DELIVERED) {
                log.info("dispatch skip (already delivered): intentId={} channel={} hash={}",
                    intent.getIntentId(), target.channel(), target.recipientHash());
                attempted++;
                continue;
            }

            // Codex 019df9ef P2 absorb: per-target REQUIRES_NEW transaction
            // boundary — provider call + delivery row + audit are atomic per
            // target; one target failure does NOT roll back earlier successful
            // sends. Provider-side dedup remains receiver responsibility.
            DispatchOutcome outcome = self.dispatchSingleTarget(intent, target, message);
            attempted++;

            switch (outcome.status) {
                case DELIVERED -> { /* ok */ }
                case FAILED, BOUNCED -> { anyFailedPermanent = true; allDelivered = false; }
                case RETRY -> allDelivered = false;
            }
        }

        // Update intent status: COMPLETED iff all delivered
        if (allDelivered && !targets.isEmpty()) {
            intent.setStatus(NotificationIntent.Status.COMPLETED);
            intentRepo.save(intent);
        } else if (anyFailedPermanent) {
            // PR3: keep PROCESSING; PR4 worker decides COMPLETED vs partial-fail terminal state
            log.info("dispatch complete with permanent failures: intentId={} retries pending",
                intent.getIntentId());
        }

        log.info("dispatch end: intentId={} attempted={} all_delivered={}",
            intent.getIntentId(), attempted, allDelivered);
        return attempted;
    }

    /**
     * Per-target dispatch — REQUIRES_NEW boundary (Codex 019df9ef P2 absorb).
     *
     * <p>Each target: adapter.send → persist delivery row → audit, all in a
     * fresh transaction. Provider call exception (or downstream persist/audit
     * fail) rolls back THIS target only; previously successful sends remain
     * committed.
     *
     * <p>Concurrency: PG unique constraint
     * {@code uq_delivery_intent_channel_recipient (intent_id, channel,
     * recipient_hash)} (V2 migration) makes parallel dispatch safe — duplicate
     * insert raises {@code DataIntegrityViolation}; caller treats as already
     * delivered (idempotent).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public DispatchOutcome dispatchSingleTarget(
        NotificationIntent intent, DeliveryTarget target, RenderedMessage message
    ) {
        ChannelAdapter adapter = adapterRegistry.get(target.channel())
            .orElseThrow(() -> new IllegalStateException(
                "adapter missing for channel '" + target.channel() + "'"
            ));

        // Pre-attempt audit
        audit.publish("DELIVERY_ATTEMPTED", intent, target.recipientHash(), target.channel(),
            Map.of("provider", target.providerKey()));

        ChannelAdapter.DeliveryAttemptResult result;
        try {
            result = adapter.send(target, message);
        } catch (RuntimeException e) {
            log.warn("adapter exception (treating as RETRY): channel={} provider={} err={}",
                target.channel(), target.providerKey(), e.getMessage());
            result = ChannelAdapter.DeliveryAttemptResult.retry(
                "exception: " + e.getClass().getSimpleName(), null);
        }

        try {
            persistDelivery(intent, target, result);
        } catch (org.springframework.dao.DataIntegrityViolationException e) {
            // Concurrent dispatch (multi-pod) created the row first → idempotent OK
            log.info("dispatch concurrent insert (already exists): intentId={} channel={} hash={}",
                intent.getIntentId(), target.channel(), target.recipientHash());
            return new DispatchOutcome(ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED);
        }

        switch (result.status()) {
            case DELIVERED -> audit.publish("DELIVERY_SUCCEEDED", intent,
                target.recipientHash(), target.channel(), additionalDetails(result));
            case FAILED, BOUNCED -> audit.publish("DELIVERY_FAILED", intent,
                target.recipientHash(), target.channel(), additionalDetails(result));
            case RETRY -> audit.publish("DELIVERY_ATTEMPTED", intent,
                target.recipientHash(), target.channel(), additionalDetails(result));
        }

        return new DispatchOutcome(result.status());
    }

    /** Internal per-target outcome record (status only — full result stored in delivery row). */
    public record DispatchOutcome(ChannelAdapter.DeliveryAttemptResult.Status status) {}

    private void persistDelivery(
        NotificationIntent intent, DeliveryTarget target, ChannelAdapter.DeliveryAttemptResult result
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setIntentId(intent.getIntentId());
        delivery.setChannel(target.channel());
        delivery.setRecipientType(mapRecipientType(target.recipientType()));
        delivery.setRecipientId(target.recipientId());
        delivery.setRecipientHash(target.recipientHash());
        delivery.setProvider(target.providerKey());
        delivery.setProviderMsgId(result.providerMessageId());
        delivery.setStatus(NotificationDelivery.Status.valueOf(result.status().name()));
        delivery.setAttemptCount(1);
        delivery.setLastAttemptAt(OffsetDateTime.now());
        if (result.status() == ChannelAdapter.DeliveryAttemptResult.Status.DELIVERED) {
            delivery.setDeliveredAt(OffsetDateTime.now());
        } else {
            delivery.setFailureReason(result.failureReason());
        }
        deliveryRepo.save(delivery);
    }

    /**
     * Map DeliveryTarget recipient type string to NotificationDelivery enum
     * (Codex 019df9ef P2 absorb: CHANNEL value introduced for slack/webhook
     * target-addressed channels — previously fell through to EXTERNAL which
     * polluted audit/analytics semantics).
     */
    private static NotificationDelivery.RecipientType mapRecipientType(String s) {
        return switch (s) {
            case "subscriber" -> NotificationDelivery.RecipientType.SUBSCRIBER;
            case "external" -> NotificationDelivery.RecipientType.EXTERNAL;
            case "channel" -> NotificationDelivery.RecipientType.CHANNEL;
            default -> throw new IllegalStateException(
                "unknown DeliveryTarget recipientType: " + s
            );
        };
    }

    private static Map<String, Object> additionalDetails(ChannelAdapter.DeliveryAttemptResult result) {
        Map<String, Object> details = new HashMap<>();
        details.put("delivery_status", result.status().name());
        if (result.providerMessageId() != null) details.put("provider_msg_id", result.providerMessageId());
        if (result.failureReason() != null) details.put("failure_reason", result.failureReason());
        if (result.providerResponseCode() != null) details.put("provider_response_code", result.providerResponseCode());
        return details;
    }
}

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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

            persistDelivery(intent, target, result);
            attempted++;

            switch (result.status()) {
                case DELIVERED -> audit.publish("DELIVERY_SUCCEEDED", intent,
                    target.recipientHash(), target.channel(),
                    additionalDetails(result));
                case FAILED, BOUNCED -> {
                    audit.publish("DELIVERY_FAILED", intent,
                        target.recipientHash(), target.channel(),
                        additionalDetails(result));
                    anyFailedPermanent = true;
                    allDelivered = false;
                }
                case RETRY -> {
                    audit.publish("DELIVERY_ATTEMPTED", intent,
                        target.recipientHash(), target.channel(),
                        additionalDetails(result));
                    allDelivered = false;
                }
            }
        }

        // Update intent status: COMPLETED iff all delivered
        if (allDelivered) {
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

    private void persistDelivery(
        NotificationIntent intent, DeliveryTarget target, ChannelAdapter.DeliveryAttemptResult result
    ) {
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setIntentId(intent.getIntentId());
        delivery.setChannel(target.channel());
        delivery.setRecipientType(NotificationDelivery.RecipientType.valueOf(
            target.recipientType().equals("subscriber") ? "SUBSCRIBER" : "EXTERNAL"
        ));
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

    private static Map<String, Object> additionalDetails(ChannelAdapter.DeliveryAttemptResult result) {
        Map<String, Object> details = new HashMap<>();
        details.put("delivery_status", result.status().name());
        if (result.providerMessageId() != null) details.put("provider_msg_id", result.providerMessageId());
        if (result.failureReason() != null) details.put("failure_reason", result.failureReason());
        if (result.providerResponseCode() != null) details.put("provider_response_code", result.providerResponseCode());
        return details;
    }
}

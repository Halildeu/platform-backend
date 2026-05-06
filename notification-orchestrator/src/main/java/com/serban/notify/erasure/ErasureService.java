package com.serban.notify.erasure;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ErasureService — KVKK §11 / GDPR Art 17 right-to-erasure (Faz 23.2 PR-B —
 * Codex 019dfae5 Q2 PARTIAL absorb).
 *
 * <p>Codex Q2 absorb:
 * <ul>
 *   <li>Sync admin endpoint (small data; async job follow-up for bulk)</li>
 *   <li>Sadece intent.payload değil; recipients_snapshot, metadata,
 *       channel_routing, preference_override içindeki PII de purge</li>
 *   <li>delivery.recipient_id null (subscriber link severance);
 *       recipient_hash KORUNUR (operational analytics; KVKK pseudonymous boundary)</li>
 *   <li>Audit append: SUBSCRIBER_ERASURE_REQUEST event (append-only RULE — silinmez)</li>
 *   <li>Idempotent: ikinci çağrı = no-op (already erased)</li>
 * </ul>
 *
 * <p>Authorization (caller responsibility): {@code ROLE_PRIVACY_OFFICER} OR
 * permission-service {@code can_erasure} relation. AdminErasureController
 * enforces via {@code @PreAuthorize}.
 */
@Service
public class ErasureService {

    private static final Logger log = LoggerFactory.getLogger(ErasureService.class);

    private final NotificationIntentRepository intentRepo;
    private final NotificationDeliveryRepository deliveryRepo;
    private final AuditEventPublisher audit;

    public ErasureService(
        NotificationIntentRepository intentRepo,
        NotificationDeliveryRepository deliveryRepo,
        AuditEventPublisher audit
    ) {
        this.intentRepo = intentRepo;
        this.deliveryRepo = deliveryRepo;
        this.audit = audit;
    }

    /**
     * Erase subscriber PII across notification data — KVKK §11.
     *
     * <p>Pipeline:
     * <ol>
     *   <li>Find all intents for (orgId, subscriberId) — by recipients_snapshot</li>
     *   <li>For each intent: payload=null, recipients_snapshot=null,
     *       metadata=null, preference_override=null (PII surface)</li>
     *   <li>For each delivery: recipient_id=null (subscriber link severance);
     *       recipient_hash KORUNUR (operational analytics)</li>
     *   <li>Audit append: SUBSCRIBER_ERASURE_REQUEST event (append-only)</li>
     * </ol>
     *
     * <p>Idempotent: second call = no-op (intent.payload already null).
     *
     * @param request erasure request (orgId + subscriberId + reason + evidence_ref)
     * @return EraseResult (intentsErased + deliveriesAnonymized)
     */
    @Transactional
    public EraseResult eraseSubscriber(EraseRequest request) {
        log.info("KVKK erasure start: orgId={} subscriberId={} reason={} evidence={}",
            request.orgId(), request.subscriberId(), request.reason(), request.evidenceRef());

        // Find all intents that have this subscriber in recipients_snapshot
        List<NotificationIntent> intents = intentRepo.findIntentsBySubscriber(
            request.orgId(), request.subscriberId()
        );

        int intentsErased = 0;
        int deliveriesAnonymized = 0;

        for (NotificationIntent intent : intents) {
            if (intent.getPayload() == null && intent.getRecipientsSnapshot() == null) {
                // Already erased — idempotent skip
                continue;
            }

            // Purge PII
            intent.setPayload(null);
            intent.setRecipientsSnapshot(null);
            intent.setMetadata(null);
            intent.setPreferenceOverride(null);
            intentRepo.save(intent);
            intentsErased++;

            // Anonymize deliveries (recipient_id null; recipient_hash KORUNUR)
            var deliveries = deliveryRepo.findByIntentId(intent.getIntentId());
            for (var delivery : deliveries) {
                if (delivery.getRecipientId() != null) {
                    delivery.setRecipientId(null);
                    deliveryRepo.save(delivery);
                    deliveriesAnonymized++;
                }
            }

            // Audit append (append-only — silinmez)
            Map<String, Object> details = new HashMap<>();
            details.put("erasure_reason", request.reason());
            details.put("evidence_ref", request.evidenceRef());
            details.put("subscriber_id", request.subscriberId());  // NOT email/phone
            details.put("deliveries_anonymized", deliveriesAnonymized);
            audit.publish("SUBSCRIBER_ERASURE_REQUEST", intent, null, null, details);
        }

        log.info("KVKK erasure complete: orgId={} subscriberId={} intents_erased={} deliveries_anonymized={}",
            request.orgId(), request.subscriberId(), intentsErased, deliveriesAnonymized);

        return new EraseResult(intentsErased, deliveriesAnonymized);
    }

    /**
     * Erasure request — KVKK admin trigger.
     *
     * @param orgId tenant boundary
     * @param subscriberId subscriber to erase
     * @param reason KVKK §11 reason (e.g., "subject_request", "expired_consent")
     * @param evidenceRef ticket/letter/audit reference (operator runbook)
     */
    public record EraseRequest(
        String orgId,
        String subscriberId,
        String reason,
        String evidenceRef
    ) {}

    /**
     * Erasure result.
     *
     * @param intentsErased intents whose PII (payload, snapshot, metadata, preference) cleared
     * @param deliveriesAnonymized delivery rows where recipient_id null'lanan
     */
    public record EraseResult(
        int intentsErased,
        int deliveriesAnonymized
    ) {}
}

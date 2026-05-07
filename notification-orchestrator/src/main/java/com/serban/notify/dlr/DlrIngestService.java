package com.serban.notify.dlr;

import com.serban.notify.audit.AuditEventPublisher;
import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import com.serban.notify.repository.NotificationDeliveryRepository;
import com.serban.notify.repository.NotificationIntentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * DLR (Delivery Receipt) ingest service — Faz 23.4 PR-F.
 *
 * <p>Provider posts terminal delivery status after SMS reaches/fails-at
 * carrier. We update the matching {@link NotificationDelivery} row and
 * append audit event {@code DELIVERY_DLR_RECEIVED}.
 *
 * <p>Status mapping (NetGSM REST v2 DLR codes):
 * <ul>
 *   <li>{@code 00} → {@link NotificationDelivery.Status#DELIVERED} terminal</li>
 *   <li>{@code 04, 05, 16, 17, 70} → {@link NotificationDelivery.Status#FAILED}
 *       terminal (carrier reject / undeliverable / expired / IYS opt-out)</li>
 *   <li>others → no status mutation; audit-only (transient ack)</li>
 * </ul>
 *
 * <p>Idempotency: same DLR posted twice for an already-terminal delivery
 * row is a no-op (lookup finds row, but status mutation gated by current
 * state). Provider-side retry safety.
 *
 * <p>Out of scope (this PR):
 * <ul>
 *   <li>Multi-provider DLR (İletimerkezi, Twilio) — provider-specific code
 *       maps belong in adapter-side translators future PR</li>
 *   <li>Cross-pod replay protection — provider DLR endpoint is idempotent
 *       at row level (status check), so duplicate POSTs across pods are safe</li>
 * </ul>
 */
@Service
public class DlrIngestService {

    private static final Logger log = LoggerFactory.getLogger(DlrIngestService.class);

    /**
     * NetGSM permanent failure codes (terminal FAILED). 17/70 KVKK opt-out
     * (IYS); 04/05/16 carrier-side undeliverable.
     */
    private static final Set<String> NETGSM_PERMANENT_FAILURE_CODES =
        Set.of("04", "05", "16", "17", "70");

    private final NotificationDeliveryRepository deliveryRepo;
    private final NotificationIntentRepository intentRepo;
    private final AuditEventPublisher audit;

    public DlrIngestService(
        NotificationDeliveryRepository deliveryRepo,
        NotificationIntentRepository intentRepo,
        AuditEventPublisher audit
    ) {
        this.deliveryRepo = deliveryRepo;
        this.intentRepo = intentRepo;
        this.audit = audit;
    }

    /**
     * Process NetGSM DLR callback.
     *
     * @return {@link DlrResult} with action taken (UPDATED / NOOP / NOT_FOUND)
     */
    @Transactional
    public DlrResult ingestNetgsm(String jobid, String code, String description) {
        String providerMsgId = "netgsm-" + jobid;

        Optional<NotificationDelivery> opt = deliveryRepo.findFirstByProviderMsgId(providerMsgId);
        if (opt.isEmpty()) {
            log.warn("dlr netgsm: delivery not found provider_msg_id={} code={}",
                providerMsgId, code);
            return new DlrResult(DlrAction.NOT_FOUND, providerMsgId, null);
        }
        NotificationDelivery delivery = opt.get();
        NotificationDelivery.Status priorStatus = delivery.getStatus();

        // Map provider code → terminal status
        NotificationDelivery.Status newStatus = mapNetgsmCode(code, priorStatus);

        // Idempotency: already in target terminal status → audit-only no-op
        boolean stateMutated = false;
        if (newStatus != null && newStatus != priorStatus) {
            // Forward-only: don't downgrade DELIVERED → FAILED via late DLR
            if (priorStatus == NotificationDelivery.Status.DELIVERED) {
                log.info("dlr netgsm: ignoring late status={} for already-DELIVERED row id={}",
                    code, delivery.getId());
            } else {
                delivery.setStatus(newStatus);
                if (newStatus == NotificationDelivery.Status.DELIVERED
                    && delivery.getDeliveredAt() == null) {
                    delivery.setDeliveredAt(OffsetDateTime.now());
                }
                if (newStatus == NotificationDelivery.Status.FAILED
                    && delivery.getPermanentFailureAt() == null) {
                    delivery.setPermanentFailureAt(OffsetDateTime.now());
                    delivery.setFailureReason("dlr netgsm code=" + code);
                }
                deliveryRepo.save(delivery);
                stateMutated = true;
            }
        }

        // Audit event (always — even on no-op, for compliance trail)
        Map<String, Object> details = new HashMap<>();
        details.put("provider", "netgsm");
        details.put("provider_code", code);
        details.put("delivery_id", delivery.getId());
        details.put("status", newStatus != null ? newStatus.name() : priorStatus.name());
        details.put("dlr_state_mutated", stateMutated);

        // Need parent intent for AuditEventPublisher.publish() contract
        Optional<NotificationIntent> intentOpt = intentRepo.findByIntentId(delivery.getIntentId());
        if (intentOpt.isPresent()) {
            audit.publish(
                "DELIVERY_DLR_RECEIVED",
                intentOpt.get(),
                delivery.getRecipientHash(),
                "sms",
                details
            );
        } else {
            log.warn("dlr netgsm: intent {} not found for delivery {} — audit skipped",
                delivery.getIntentId(), delivery.getId());
        }

        log.info("dlr netgsm processed: provider_msg_id={} code={} prior={} new={} mutated={}",
            providerMsgId, code, priorStatus, newStatus, stateMutated);

        DlrAction action = stateMutated ? DlrAction.UPDATED : DlrAction.NOOP;
        return new DlrResult(action, providerMsgId, newStatus != null ? newStatus : priorStatus);
    }

    /**
     * NetGSM DLR code → notification_delivery.Status mapping.
     *
     * @return target status, or null if code is transient (no terminal mutation)
     */
    private static NotificationDelivery.Status mapNetgsmCode(
        String code, NotificationDelivery.Status priorStatus
    ) {
        if ("00".equals(code)) {
            return NotificationDelivery.Status.DELIVERED;
        }
        if (code != null && NETGSM_PERMANENT_FAILURE_CODES.contains(code)) {
            return NotificationDelivery.Status.FAILED;
        }
        return null;  // transient — keep current status
    }

    /** Result of DLR ingest call (controller reports back to provider). */
    public record DlrResult(
        DlrAction action,
        String providerMsgId,
        NotificationDelivery.Status currentStatus
    ) {}

    public enum DlrAction {
        /** Delivery row state mutated (status terminal transition applied). */
        UPDATED,
        /** Delivery row found but no state mutation (idempotent re-call or transient code). */
        NOOP,
        /** Delivery row not found by provider_msg_id (provider sent unknown id). */
        NOT_FOUND
    }
}

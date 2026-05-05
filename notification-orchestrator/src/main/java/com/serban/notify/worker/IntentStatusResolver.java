package com.serban.notify.worker;

import com.serban.notify.domain.NotificationDelivery;
import com.serban.notify.domain.NotificationIntent;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Intent terminal status resolver (Codex 019dfa47 Q4 REVISE absorb).
 *
 * <p>Intent terminal state computation rules:
 * <ul>
 *   <li>Tüm delivery {@code DELIVERED} → {@link NotificationIntent.Status#COMPLETED}</li>
 *   <li>En az 1 {@code DELIVERED} + en az 1 terminal failure (FAILED/BOUNCED) →
 *       {@link NotificationIntent.Status#PARTIALLY_FAILED}</li>
 *   <li>Hiç {@code DELIVERED} yok, tümü terminal non-delivered →
 *       {@link NotificationIntent.Status#FAILED}</li>
 *   <li>Hâlâ {@code RETRY} delivery var → no terminal transition (intent
 *       PROCESSING kalır; PR4 worker re-attempt edecek)</li>
 *   <li>Hâlâ {@code PENDING} delivery var → no terminal transition</li>
 * </ul>
 *
 * <p>{@link NotificationIntent.Status#EXPIRED} ayrı zaman politikası ile set
 * edilir (provider failure DEĞİL); bu resolver dokunmaz.
 */
@Component
public class IntentStatusResolver {

    /**
     * Resolve terminal status from a list of deliveries.
     *
     * @return target Status if terminal transition possible; {@code null} if
     *         intent must remain PROCESSING (RETRY/PENDING outstanding)
     */
    public NotificationIntent.Status resolve(List<NotificationDelivery> deliveries) {
        if (deliveries == null || deliveries.isEmpty()) {
            return null;  // no deliveries yet — intent in early PROCESSING
        }

        boolean anyOutstanding = false;
        boolean anyDelivered = false;
        boolean anyTerminalFailure = false;

        for (NotificationDelivery d : deliveries) {
            switch (d.getStatus()) {
                case DELIVERED -> anyDelivered = true;
                case FAILED, BOUNCED, BLOCKED_BY_PREFERENCE,
                     BLOCKED_BY_AUTHZ, BLOCKED_BY_IDEMPOTENCY,
                     BLOCKED_EXTERNAL_NOT_ALLOWED -> anyTerminalFailure = true;
                case RETRY, PENDING -> anyOutstanding = true;
            }
        }

        if (anyOutstanding) {
            return null;  // RETRY or PENDING — keep PROCESSING
        }
        if (anyDelivered && anyTerminalFailure) {
            return NotificationIntent.Status.PARTIALLY_FAILED;
        }
        if (anyDelivered) {
            return NotificationIntent.Status.COMPLETED;
        }
        // No DELIVERED + no outstanding → FAILED
        return NotificationIntent.Status.FAILED;
    }
}

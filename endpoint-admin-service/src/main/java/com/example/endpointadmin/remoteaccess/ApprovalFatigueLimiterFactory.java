package com.example.endpointadmin.remoteaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 D10 approval-chain (Codex 019ebe06) — selects the {@link ApprovalFatigueLimiter} with a blocking
 * matrix at construction (= bean creation = STARTUP fail-fast):
 * <ul>
 *   <li><b>IN_MEMORY</b> — the process-local reference limiter. FORBIDDEN in a production-like profile: a
 *       process-local fatigue cap is bypassable across restart / replicas, so a prod broker MUST use a durable
 *       limiter (Codex: while the canonical + grant resolvers are prod-forbidden in-memory, an in-memory fatigue
 *       would let the dual-control volume cap be defeated by a restart).</li>
 *   <li><b>DURABLE_DISTRIBUTED</b> — the real (DB/Redis-backed) limiter. NOT YET IMPLEMENTED (the live slice) →
 *       rejected rather than half-wired.</li>
 * </ul>
 */
public final class ApprovalFatigueLimiterFactory {

    private static final Logger log = LoggerFactory.getLogger(ApprovalFatigueLimiterFactory.class);

    public enum LimiterType { IN_MEMORY, DURABLE_DISTRIBUTED }

    private ApprovalFatigueLimiterFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access approval-fatigue limiter config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param productionLikeProfile when true, the process-local IN_MEMORY limiter is REFUSED
     * @throws IllegalStateException when forbidden (fail-fast startup); IllegalArgumentException on bad bounds
     */
    public static ApprovalFatigueLimiter create(LimiterType type, int maxApprovalsPerWindow, long windowMillis,
                                                boolean productionLikeProfile) {
        LimiterType t = type == null ? LimiterType.IN_MEMORY : type; // placeholder default (non-prod)
        switch (t) {
            case IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("approval-fatigue limiter IN_MEMORY is process-local (bypassable across "
                            + "restart/replicas) and is forbidden in a production-like profile — use a "
                            + "durable/distributed limiter so the dual-control volume cap holds");
                }
                return new ApprovalFatigueLimiter(maxApprovalsPerWindow, windowMillis);
            }
            case DURABLE_DISTRIBUTED -> throw reject("approval-fatigue limiter DURABLE_DISTRIBUTED is not yet "
                    + "implemented (the live slice) — refusing rather than half-wiring the fatigue cap");
            default -> throw reject("unreachable approval-fatigue limiter type " + t);
        }
    }
}

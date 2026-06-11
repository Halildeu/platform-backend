package com.example.endpointadmin.remoteaccess;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * Faz 22.6 B2 — the live revocation CHANNEL, deliberately separate from {@link TokenLifecycleStore}
 * (Codex 019eb54b lock-down): the store owns durable jti state; the feed fans a revocation out to every
 * broker replica's heartbeat in real time. Keeping them separate lets the prod transport (Redis pub/sub,
 * DB polling, or an external queue) slot in without touching the store interface.
 *
 * <p>The {@link RevocationEvent#revokedAt} is the {@code t0} marker for the hard-kill SLO
 * ({@code revocation_latency_ms}, criterion #6: P95 ≤ 5s). All times come from one trusted clock source.
 */
public interface TokenRevocationFeed {

    /** Severity hint for prioritising fanout / alerting (does not change the fail-closed kill behaviour). */
    enum Severity { ROUTINE, URGENT, SECURITY_INCIDENT }

    /**
     * A revocation, carrying the correlation metadata needed for the SLO + audit (criterion #5/#6/#10).
     *
     * @param jti                 the revoked token id
     * @param revokedAt           t0 — when revocation was decided (SLO start)
     * @param reason              short, non-sensitive reason code (e.g. OPERATOR_ABORT, POLICY_CHANGE)
     * @param requestId           correlation id tying this to the request/incident
     * @param correlatedSessionId the live session this targets (if known)
     * @param revokedBy           the principal that revoked (audit; not a secret)
     * @param severity            fanout/alert priority
     */
    record RevocationEvent(
            String jti,
            Instant revokedAt,
            String reason,
            String requestId,
            String correlatedSessionId,
            String revokedBy,
            Severity severity) {
    }

    /** Publish a revocation to all subscribers. MUST be delivered fail-closed (lost delivery ≠ stays live). */
    void publish(RevocationEvent event);

    /** Register a heartbeat/consumer that reacts to revocations (e.g. revoke in the store + kill the session). */
    void subscribe(Consumer<RevocationEvent> subscriber);
}

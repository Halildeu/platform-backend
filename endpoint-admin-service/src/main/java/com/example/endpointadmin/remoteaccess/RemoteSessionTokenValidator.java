package com.example.endpointadmin.remoteaccess;

import java.time.Instant;
import java.util.Set;

/**
 * Fail-closed validation of a {@link RemoteSessionToken} at connect time (ADR-0033 §5).
 * Every check defaults to <b>deny</b>. A token is accepted only if ALL hold:
 * <ol>
 *   <li>structurally valid + TTL within the 4h hard cap,</li>
 *   <li>not expired at {@code now},</li>
 *   <li>bound to the exact (session, device, operator) being connected,</li>
 *   <li>its {@code jti} has not been seen before (single-use replay guard),</li>
 *   <li>the requested capabilities are a subset of the token's broker-approved allowlist
 *       (false-advertising / capability-mismatch guard).</li>
 * </ol>
 * No side effects except consuming the {@code jti} on a successful accept.
 */
public final class RemoteSessionTokenValidator {

    /**
     * Single-use replay guard. {@code recordIfAbsent} returns {@code true} iff this jti was newly
     * recorded (not seen before).
     *
     * <p><b>Contract (Codex 019eb522 REVISE absorb — TOCTOU):</b> implementations MUST be atomic /
     * thread-safe: under concurrent calls with the same jti exactly one call returns {@code true}.
     * A non-atomic cache (e.g. a plain {@code HashSet}) would let two connect attempts accept the same
     * token. Production should back this with an atomic store (e.g. Redis {@code SET NX} / a DB unique
     * constraint), not in-process state, so the guard survives across broker replicas.
     */
    public interface JtiReplayCache {
        boolean recordIfAbsent(String jti);
    }

    private final JtiReplayCache replayCache;

    public RemoteSessionTokenValidator(JtiReplayCache replayCache) {
        this.replayCache = replayCache;
    }

    /**
     * Internal / AUDIT-ONLY decision. The specific {@code DENY_*} reason is a high-signal audit/telemetry
     * value but MUST NOT be surfaced to the client (Codex 019eb54b absorb — error-oracle / enumeration
     * guard): two callers presenting different bad tokens must be indistinguishable on the wire. Map to
     * {@link #clientFacing(Decision)} ({@link ClientDecision#DENIED}) for any external response, and the
     * caller MUST respond in constant time + under a rate limit so timing/branching is not an oracle.
     */
    public enum Decision {
        ACCEPT,
        DENY_MALFORMED,
        DENY_EXPIRED,
        DENY_BINDING_MISMATCH,
        DENY_REPLAYED,
        DENY_CAPABILITY_MISMATCH
    }

    /** The ONLY decision shape allowed on the wire (uniform — no reason leak). */
    public enum ClientDecision {
        ACCEPT,
        DENIED
    }

    /**
     * Collapse any internal {@link Decision} to the uniform client-facing {@link ClientDecision}. Every
     * {@code DENY_*} → {@link ClientDecision#DENIED}; only {@link Decision#ACCEPT} → ACCEPT. Use this for
     * every external response so the specific failure reason never leaks (anti-oracle).
     */
    public static ClientDecision clientFacing(Decision decision) {
        return decision == Decision.ACCEPT ? ClientDecision.ACCEPT : ClientDecision.DENIED;
    }

    /**
     * @param token                 the presented token
     * @param now                   current instant
     * @param sessionId             session being connected
     * @param targetDeviceId        device being connected
     * @param operatorUserId        operator connecting
     * @param requestedCapabilities capabilities the operator is asking to use this session
     * @return a fail-closed decision; only {@link Decision#ACCEPT} permits the connect to proceed.
     */
    public Decision validate(RemoteSessionToken token, Instant now,
                             String sessionId, String targetDeviceId, String operatorUserId,
                             Set<RemoteSessionCapability> requestedCapabilities) {
        if (token == null || token.jti() == null || token.jti().isBlank() || !token.ttlWithinHardCap()) {
            return Decision.DENY_MALFORMED; // blank/whitespace jti is malformed (replay-key integrity)
        }
        if (token.isExpiredAt(now)) {
            return Decision.DENY_EXPIRED;
        }
        if (!token.isBoundTo(sessionId, targetDeviceId, operatorUserId)) {
            return Decision.DENY_BINDING_MISMATCH;
        }
        Set<RemoteSessionCapability> requested = requestedCapabilities == null ? Set.of() : requestedCapabilities;
        if (!token.approvedCapabilities().containsAll(requested)) {
            return Decision.DENY_CAPABILITY_MISMATCH; // agent can only downscope, never widen
        }
        // Replay check LAST so a malformed/mismatched token never burns a jti slot.
        if (replayCache == null || !replayCache.recordIfAbsent(token.jti())) {
            return Decision.DENY_REPLAYED;
        }
        return Decision.ACCEPT;
    }
}

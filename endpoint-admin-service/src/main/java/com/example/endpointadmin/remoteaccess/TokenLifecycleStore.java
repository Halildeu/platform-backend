package com.example.endpointadmin.remoteaccess;

import java.time.Instant;

/**
 * Faz 22.6 B2 — the single source of truth for session-token {@code jti} lifecycle (ADR-0033 §5/§9b,
 * Codex 019eb54b criteria #1/#4/#7/#8). Replaces the skeleton's in-process {@code JtiReplayCache} with
 * a revocation-aware, time-aware, atomic, distributable store.
 *
 * <p><b>Contract (MUST hold in every impl):</b>
 * <ul>
 *   <li><b>Atomic single-use:</b> {@link #consume} is atomic — under N concurrent calls for the same
 *       jti, at most ONE returns {@link ConsumeOutcome#ACCEPTED} (Redis Lua / DB CAS in prod). The
 *       availability + expiry + state checks happen in the SAME atomic step (no TOCTOU between them).</li>
 *   <li><b>Time-aware:</b> liveness is deterministic from {@code (state, expiresAt, now)} — a token past
 *       its {@code expiresAt} is not live even if no explicit {@link #expire} call has run yet.</li>
 *   <li><b>Fail-closed:</b> if the store is unreachable, {@link #consume} returns
 *       {@link ConsumeOutcome#STORE_UNAVAILABLE} and {@link #isTokenLive} returns {@code false} — never
 *       fail-open (criterion #7). A partition STOPS new sessions + re-validation.</li>
 *   <li><b>Revocation is authoritative:</b> {@link #revoke} always wins (even over EXPIRED) for forensic
 *       clarity (TOKEN_REVOKED ≠ EXPIRED), and drives the hard-kill path — once revoked,
 *       {@link #isTokenLive} is {@code false} on the very next heartbeat sample (criterion #4).</li>
 * </ul>
 */
public interface TokenLifecycleStore {

    /** Result of an atomic connect-time consume. Only {@link #ACCEPTED} permits the connect. */
    enum ConsumeOutcome {
        ACCEPTED,
        ALREADY_USED,
        REVOKED,
        EXPIRED,
        INVALID,
        /** Store unreachable → fail-closed deny (never treat as accept). */
        STORE_UNAVAILABLE;

        public boolean isAccepted() {
            return this == ACCEPTED;
        }
    }

    /**
     * Idempotent mutation result. {@link #UPDATED} = a real change; {@link #NOOP} = already in that state;
     * {@link #STORE_UNAVAILABLE} = the mutation FAILED at the store (Codex 019eb54b absorb — a failed
     * revoke MUST surface, not masquerade as a no-op, so the revocation fanout retries/alerts instead of
     * assuming the kill landed).
     */
    enum MutationOutcome {
        UPDATED,
        NOOP,
        STORE_UNAVAILABLE;

        public boolean isApplied() {
            return this == UPDATED;
        }
    }

    /**
     * Liveness check result with the root cause (Codex 019eb54b lock-down): the runtime treats ONLY
     * {@link #LIVE} as live ({@link #isLive()}), but records the specific reason for revocation-fanout /
     * SLO metrics + audit. Fail-closed: every non-LIVE value means the session must be killed.
     *
     * <p>NOTE: all {@code Instant}s passed to this store MUST come from a single trusted/monotonic clock
     * source so TTL/expiry are consistent across the prod Redis Lua / DB CAS backings.
     */
    enum TokenLiveCheckResult {
        LIVE,
        REVOKED,
        EXPIRED,
        INVALID,
        STORE_UNAVAILABLE,
        NOT_FOUND;

        public boolean isLive() {
            return this == LIVE;
        }
    }

    /**
     * Atomically transition {@code UNSEEN → USED} for a one-time connect, recording {@code expiresAt}.
     * Returns {@link ConsumeOutcome#ACCEPTED} only on the (single) winning call; replay / revoked / expired
     * (incl. {@code now >= expiresAt}) / invalid / store-down all deny. Availability + expiry + state are
     * evaluated in one atomic step.
     */
    ConsumeOutcome consume(String jti, Instant expiresAt, Instant now);

    /** Mark a jti revoked (authoritative — always wins). Idempotent; drives hard-kill of any live session. */
    MutationOutcome revoke(String jti);

    /** Mark a jti expired (TTL elapsed). Idempotent; does NOT override a REVOKED jti. */
    MutationOutcome expire(String jti);

    /**
     * Heartbeat liveness check (does NOT consume). Returns {@link TokenLiveCheckResult#LIVE} ONLY if the
     * jti is {@code USED}, the store is reachable, AND {@code now} is before its recorded {@code expiresAt};
     * otherwise the specific fail-closed reason. The runtime heartbeat maps {@code result.isLive()} to
     * {@code RemoteSessionPreconditions.tokenBound}, so a revoked/expired token makes
     * {@link RemoteSessionStateMachine#reevaluateActive} kill the session (and records the reason for SLO).
     */
    TokenLiveCheckResult isTokenLive(String jti, Instant now);
}

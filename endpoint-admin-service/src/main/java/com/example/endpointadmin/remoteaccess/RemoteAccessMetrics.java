package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 B2 — canonical metric names (Codex 019eb54b criterion #10: no acceptance without these).
 * The runtime emits them; the D10 gate asserts the hard-kill / revocation SLOs against them
 * (criterion #6: revocation fanout P95 ≤ 5s / P99 ≤ 10s / max ≤ 30s).
 */
public final class RemoteAccessMetrics {

    /** t0(revoke) → t3(session terminated): end-to-end hard-kill latency. SLO P95 ≤ 5s. */
    public static final String REVOCATION_LATENCY_MS = "remote_access_revocation_latency_ms";
    /** Control-plane decision → termination-ack latency. */
    public static final String HARD_KILL_LATENCY_MS = "remote_access_hard_kill_latency_ms";
    /** Count of heartbeat samples rejected by the monotonicity guard (stale/out-of-order). */
    public static final String HEARTBEAT_STALE_REJECT = "remote_access_heartbeat_stale_reject_total";
    /** Count of jti replay attempts denied (single-use guard). */
    public static final String JTI_REPLAY_REJECT = "remote_access_jti_replay_reject_total";
    /** Count of connect attempts throttled, tagged by axis (operator/network/session). */
    public static final String RATE_LIMIT_THROTTLED = "remote_access_rate_limit_throttled_total";
    /** Invariant alarm: a non-uniform/non-constant-time DENIED was observed on the wire. Should stay 0. */
    public static final String CONSTANT_TIME_DENIED_VIOLATIONS = "remote_access_constant_time_denied_violations_total";
    /** Count of consume() / liveness checks that hit a store-unavailable (fail-closed) path. */
    public static final String STORE_UNAVAILABLE = "remote_access_store_unavailable_total";

    // ---- B2.2c live-runtime driver (Codex 019eb54b Q1/Q2/Q3/Q4/Q5 absorb) ----

    /**
     * A revocation caught by the POLL backstop (a DROPPED feed delivery) rather than the push path —
     * proves criterion #7 fail-closed reliability. A rising rate means the feed transport is lossy.
     */
    public static final String HARD_KILL_POLL_RECOVERY = "remote_access_hard_kill_poll_recovery_total";
    /**
     * Invariant alarm: more than one LOCAL ACTIVE session was bound to a single jti — the single-owner
     * assumption underpinning lock-free multi-instance safety (Codex Q1) was violated. Should stay 0.
     */
    public static final String SESSION_OWNERSHIP_CONFLICT = "remote_access_session_ownership_conflict_total";
    /**
     * Count of revocations where the event clock and the store's {@code revoked_at} disagreed (app↔store
     * skew). The SLO anchors on the store value; this meters how far the event clock drifted from it.
     */
    public static final String REVOCATION_CLOCK_SKEW = "remote_access_revocation_clock_skew_total";
    /**
     * Subset alarm: t0({@code revoked_at}) was AFTER the decision clock ({@code now < t0}) — the latency
     * sample is unreliable and is EXCLUDED from the P95 so a clock glitch cannot deflate the SLO (#10).
     */
    public static final String REVOCATION_NEGATIVE_LATENCY = "remote_access_revocation_negative_latency_total";
    /** Rows purged by a cleanup run (expired, non-REVOKED). */
    public static final String CLEANUP_PURGED_ROWS = "remote_access_cleanup_purged_rows_total";
    /** Wall-clock duration of a cleanup run — DB-cost observability for the advisory-locked cleaner (Q4). */
    public static final String CLEANUP_DURATION_MS = "remote_access_cleanup_duration_ms";

    private RemoteAccessMetrics() {
    }
}

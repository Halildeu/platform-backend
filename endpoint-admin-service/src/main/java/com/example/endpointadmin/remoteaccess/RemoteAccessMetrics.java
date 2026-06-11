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

    private RemoteAccessMetrics() {
    }
}

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
    /**
     * A cleanup run that found the advisory lock already held by another replica (Codex Q4 follow-up) —
     * expected to be non-zero in a multi-replica deployment; a {@code 0} rate means only one replica ever
     * schedules cleanup (also fine). Pure observability, not an alarm.
     */
    public static final String CLEANUP_LOCK_CONTENDED = "remote_access_cleanup_lock_contended_total";

    /**
     * <b>The hard-kill DENOMINATOR</b> (Codex 019eb54b Q3 follow-up): every hard-kill decision, whether its
     * latency was a clean SLO sample, was {@code STORE_UNAVAILABLE}, or was {@link #REVOCATION_NEGATIVE_LATENCY}.
     * Excluding the unreliable samples from the P95 is correct, but on its own it would HIDE the SLO's
     * reliability dimension — so this denominator makes the failure ratios computable + alarmable:
     * <ul>
     *   <li>{@code unavailable_ratio  = }{@link #STORE_UNAVAILABLE}{@code  / HARD_KILL_TOTAL} — alarm if &gt; 0</li>
     *   <li>{@code unmeasured_rate    = (}{@link #STORE_UNAVAILABLE}{@code  + }{@link #REVOCATION_NEGATIVE_LATENCY}{@code ) / HARD_KILL_TOTAL}</li>
     * </ul>
     * The P95/P99 timer ({@link #REVOCATION_LATENCY_MS}) is then read together with these ratios, never alone.
     */
    public static final String HARD_KILL_TOTAL = "remote_access_hard_kill_total";

    /**
     * A token consumed WITHOUT a cert binding — the legacy 3-arg {@code consume} (null thumbprint).
     * Visibility for the B1.1 migration (Codex 019eb54b B1.1a REVISE): a non-zero rate means callers
     * still issue unbound tokens. Emitted by the enforcement layer ({@link CertBoundConsumeGate}, wired in
     * {@code ScheduledRevocationDriver}) on every ACCEPTED legacy-unbound consume — only possible under
     * the explicit {@code legacy-unbound-allowed} migration flag (B1.1c check-list #2); the store stays
     * pure. Watch it flatline at zero before flipping the flag back to reject.
     */
    public static final String LEGACY_UNBOUND_ISSUANCE = "remote_access_legacy_unbound_issuance_total";

    /**
     * A connect denied or a live session killed on cert-binding grounds (B1.1c): tag {@code cause} with
     * the {@link CertBindingGuard.Decision} / refined {@code KillReason} (MISMATCH = possible token theft
     * → alarmable; PRESENTED_MISSING = transport lost its client cert; UNBOUND_REJECTED = legacy token
     * under the reject policy). Declared as the contract now; the C/D cert-sampling ingest driver emits it
     * (the B2.2c reconciler is cert-unsampled by design, so it never produces these).
     */
    public static final String CERT_BINDING_REJECT_TOTAL = "remote_access_cert_binding_reject_total";

    // ---- Faz 22.6 T-2b / #1588 broker-side DATA-stream consumption (Codex 019ecbc5) ----

    /** Accepted DATA frames dispatched to the data-plane handler (the broker actually consumed the stream). */
    public static final String BRIDGE_DATA_FRAMES = "remote_access_bridge_data_frames_total";
    /** Accepted DATA-frame payload bytes — stream throughput. */
    public static final String BRIDGE_DATA_BYTES = "remote_access_bridge_data_bytes_total";
    /**
     * DATA frames rejected by the transport guard, tagged {@code reason} with a COARSE fixed category
     * (seq / too-large / channel / payload / stream-id / envelope / other) — bounded cardinality, never the
     * raw defect string. A rising {@code seq} rate means a lossy/misordered producer.
     */
    public static final String BRIDGE_DATA_DEFECTS = "remote_access_bridge_data_defects_total";
    /**
     * The data-plane handler threw on an accepted frame: the DATA stream is closed (transport-level), the
     * frame is dropped, and the counter rises. The INERT T-2b handler never throws, so this stays 0 until a
     * real (owner-gated) consumer is wired — it is NEVER a session kill from the transport.
     */
    public static final String BRIDGE_DATA_HANDLER_ERRORS = "remote_access_bridge_data_handler_errors_total";

    private RemoteAccessMetrics() {
    }
}

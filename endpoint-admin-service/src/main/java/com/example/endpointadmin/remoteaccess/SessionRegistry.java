package com.example.endpointadmin.remoteaccess;

import java.util.List;

/**
 * Faz 22.6 B2.2c — the REPLICA-LOCAL view of live remote sessions the revocation driver reconciles
 * (Codex 019eb54b Q1). In the broker model a given session's tunnel terminates on EXACTLY ONE replica,
 * so this registry is intentionally local + in-process: the driver kills only the sessions it owns,
 * which — together with the store's idempotent mutations (DB-CAS revoke-wins, one-way ACTIVE→terminal,
 * idempotent cleanup DELETE) — makes the cluster-wide revocation fanout lock-free, with NO leader
 * election needed for this slice.
 *
 * <p><b>Owner-binding contract (the assumption this layer rests on):</b> single-ownership MUST be
 * guaranteed OUTSIDE this layer — the tunnel terminates on one replica, so a jti maps to at most one
 * local ACTIVE session per replica. If a future deployment cannot guarantee that (e.g. a session can be
 * ACTIVE on two replicas during failover), this local model is unsafe and session state MUST instead be
 * persisted with a DB-CAS terminal-state guard (deferred to the tunnel-runtime slice C/D). The driver
 * meters {@link RemoteAccessMetrics#SESSION_OWNERSHIP_CONFLICT} so any violation of the assumption is
 * observable rather than silent, and treats a conflict fail-closed (kills every matching session).
 */
public interface SessionRegistry {

    /** A snapshot of every ACTIVE session this replica owns (for the periodic fail-closed poll). */
    List<RemoteSessionHeartbeat.SessionSnapshot> localActiveSessions();

    /**
     * The local ACTIVE session(s) bound to {@code jti}. Normally 0 or 1; a size &gt; 1 is an
     * ownership conflict the driver meters + handles fail-closed. Never returns {@code null}.
     */
    List<RemoteSessionHeartbeat.SessionSnapshot> findActiveByJti(String jti);
}

package com.example.endpointadmin.remoteaccess;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Faz 22.6 B2.2c — the PURE revocation→hard-kill driver LOGIC (Codex 019eb54b B2.2c). It binds the three
 * already-merged primitives — the authoritative {@link TokenLifecycleStore}, the pure
 * {@link RemoteSessionHeartbeat} brain, and the replica-local {@link SessionRegistry} — into the two
 * fail-closed paths that actually kill a live session when its token is revoked. No scheduling, no I/O
 * beyond the injected store: the {@code @Scheduled} wiring + the metric emission live in
 * {@link ScheduledRevocationDriver} (the only non-pure-testable surface), so every decision here is unit-
 * and Testcontainers-provable.
 *
 * <ul>
 *   <li><b>PUSH</b> ({@link #onRevocation}) — react to a {@link TokenRevocationFeed.RevocationEvent}:
 *       apply the authoritative {@link TokenLifecycleStore#revoke} (idempotent, revoke-wins), anchor the
 *       SLO {@code t0} on the store's recorded {@code revoked_at} (NOT the event clock — Codex Q2), then
 *       kill the owning local session(s). The low-latency path (criterion #6, P95 ≤ 5s).</li>
 *   <li><b>POLL</b> ({@link #pollReconcile}) — the fail-closed BACKSTOP (criterion #7): re-evaluate every
 *       local ACTIVE session against the store on a short cadence. A {@code TOKEN_REVOKED} kill here means
 *       the push event was DROPPED (metered as {@link RemoteAccessMetrics#HARD_KILL_POLL_RECOVERY}); a
 *       {@code STORE_UNAVAILABLE} read fails closed (partition ⇒ kill, no re-validation pass) without
 *       needing to read REVOKED rows — so a delta-poll's blind spot is avoided.</li>
 * </ul>
 *
 * <p><b>Multi-instance safety</b> rests on {@link SessionRegistry}'s single-owner contract (a session's
 * tunnel terminates on one replica) + idempotent store mutations — no leader election this slice. A
 * single jti resolving to &gt;1 local ACTIVE session is an ownership conflict: it is metered
 * ({@link RemoteAccessMetrics#SESSION_OWNERSHIP_CONFLICT}) and handled fail-closed (every match killed).
 */
public final class RemoteSessionRevocationReconciler {

    private final TokenLifecycleStore store;
    private final RemoteSessionHeartbeat heartbeat;

    public RemoteSessionRevocationReconciler(TokenLifecycleStore store, RemoteSessionHeartbeat heartbeat) {
        this.store = store;
        this.heartbeat = heartbeat;
    }

    /** Which path produced a kill — kept distinct so the feed-drop backstop is observable (Codex Q3). */
    public enum Trigger { PUSH, POLL }

    /**
     * The outcome of reconciling ONE local session. Carries every signal the driver meters so the pure
     * logic stays I/O-free yet fully observable.
     *
     * @param latencyMillis     {@code now − t0(store revoked_at)}, clamped ≥ 0 by the heartbeat; the SLO sample
     * @param negativeLatency   {@code now < t0} — the sample is unreliable, EXCLUDED from the P95 (criterion #10)
     * @param storeUnavailable  a fail-closed store-down kill — EXCLUDED from the revocation P95 (criterion #7)
     * @param feedDropRecovery  POLL caught a {@code TOKEN_REVOKED} the push path missed (criterion #7)
     * @param ownershipConflict &gt;1 local ACTIVE session was bound to this jti (single-owner violated, Q1)
     * @param eventDbSkewMillis {@code |event.revokedAt − store.revoked_at|}, PUSH only (app↔store skew); 0 otherwise
     */
    public record ReconcileOutcome(
            String sessionId,
            String jti,
            Trigger trigger,
            boolean killed,
            RemoteSessionStateMachine.KillReason reason,
            long latencyMillis,
            boolean negativeLatency,
            boolean storeUnavailable,
            boolean feedDropRecovery,
            boolean ownershipConflict,
            long eventDbSkewMillis) {
    }

    /**
     * PUSH path. Returns one {@link ReconcileOutcome} per owning local session (empty if none own this jti
     * on this replica, which is normal — another replica owns it, or it already ended). A non-empty list
     * with {@code killed=false} cannot occur here: the token is REVOKED before evaluation, so the heartbeat
     * always kills (fail-closed even under a store partition, where the reason is {@code STORE_UNAVAILABLE}).
     */
    public List<ReconcileOutcome> onRevocation(TokenRevocationFeed.RevocationEvent ev,
                                               SessionRegistry registry, Instant now) {
        List<ReconcileOutcome> out = new ArrayList<>();
        if (ev == null || ev.jti() == null || ev.jti().isBlank() || registry == null || now == null) {
            return out;
        }
        String jti = ev.jti();
        // 1. Authoritative revoke. A FAILED revoke (store down) surfaces but does NOT stop the kill — the
        //    heartbeat's store read also fails closed, so the session still dies (criterion #7).
        boolean storeDown =
                store.revoke(jti) == TokenLifecycleStore.MutationOutcome.STORE_UNAVAILABLE;
        // 2. Source-bound t0: the store's recorded revoked_at; fall back to the event clock only if the
        //    store has no anchor (e.g. partition). Meter how far the event clock drifted from the store.
        Instant dbT0 = store.revokedAt(jti).orElse(null);
        Instant t0 = dbT0 != null ? dbT0 : ev.revokedAt();
        long eventDbSkew = (ev.revokedAt() != null && dbT0 != null)
                ? Math.abs(Duration.between(ev.revokedAt(), dbT0).toMillis())
                : 0L;
        // 3. Owner sessions on this replica. >1 ⇒ ownership conflict (metered + fail-closed: kill all).
        List<RemoteSessionHeartbeat.SessionSnapshot> targets = registry.findActiveByJti(jti);
        boolean conflict = targets.size() > 1;
        for (RemoteSessionHeartbeat.SessionSnapshot snap : targets) {
            out.add(killOne(snap, t0, Trigger.PUSH, now, storeDown, conflict, false, eventDbSkew));
        }
        return out;
    }

    /**
     * POLL path — the fail-closed backstop over EVERY local ACTIVE session (criterion #7). A live token
     * stays ACTIVE (killed=false, no latency); a revoked/expired/partitioned token is killed. A
     * {@code TOKEN_REVOKED} kill is flagged {@code feedDropRecovery} because, by the time the poll runs,
     * the push path would already have removed any session it killed — so a surviving revoked session
     * means the feed delivery was lost.
     */
    public List<ReconcileOutcome> pollReconcile(SessionRegistry registry, Instant now) {
        List<ReconcileOutcome> out = new ArrayList<>();
        if (registry == null || now == null) {
            return out;
        }
        List<RemoteSessionHeartbeat.SessionSnapshot> live = registry.localActiveSessions();
        // ownership-conflict detection across the local set: the same jti on more than one ACTIVE session.
        Map<String, Integer> byJti = new HashMap<>();
        for (RemoteSessionHeartbeat.SessionSnapshot s : live) {
            if (s.jti() != null) {
                byJti.merge(s.jti(), 1, Integer::sum);
            }
        }
        for (RemoteSessionHeartbeat.SessionSnapshot snap : live) {
            // peek the store's revoked_at so a poll-recovered kill is STILL source-bound for the SLO.
            Instant t0 = (snap.jti() == null) ? null : store.revokedAt(snap.jti()).orElse(null);
            boolean conflict = snap.jti() != null && byJti.getOrDefault(snap.jti(), 0) > 1;
            out.add(killOne(snap, t0, Trigger.POLL, now, false, conflict, true, 0L));
        }
        return out;
    }

    /**
     * Run one session through the heartbeat brain and project the decision onto a {@link ReconcileOutcome}.
     * The synthetic sample carries all non-token guarantees as held (the heartbeat re-reads token liveness
     * from the authoritative store itself) and the source-bound {@code t0} for the latency. The sample seq
     * is {@code lastAppliedSeq + 1} so it is always "fresh" for THIS session — the reconciler is a
     * synchronous store-driven check, not an out-of-order network sample, so the monotonicity guard must
     * not spuriously reject it; cross-session ordering is irrelevant because the guard is per-session.
     */
    private ReconcileOutcome killOne(RemoteSessionHeartbeat.SessionSnapshot snap, Instant t0, Trigger trigger,
                                     Instant now, boolean storeDownRevoke, boolean conflict,
                                     boolean feedDropEligible, long eventDbSkewMillis) {
        long seq = snap.lastAppliedSeq() + 1;
        RemoteSessionHeartbeat.PreconditionSample sample =
                new RemoteSessionHeartbeat.PreconditionSample(true, true, true, true, true, t0);
        RemoteSessionHeartbeat.HeartbeatDecision d = heartbeat.evaluate(snap, sample, seq, now);
        boolean storeDownKill = storeDownRevoke
                || d.reason() == RemoteSessionStateMachine.KillReason.STORE_UNAVAILABLE;
        boolean feedDrop = feedDropEligible && d.kill()
                && d.reason() == RemoteSessionStateMachine.KillReason.TOKEN_REVOKED;
        return new ReconcileOutcome(
                snap.sessionId(), snap.jti(), trigger, d.kill(), d.reason(),
                d.latencyMillis(), d.clockSkew(), storeDownKill, feedDrop, conflict, eventDbSkewMillis);
    }
}

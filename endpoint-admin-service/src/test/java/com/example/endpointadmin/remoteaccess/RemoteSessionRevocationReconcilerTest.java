package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B2.2c — pure logic tests for {@link RemoteSessionRevocationReconciler} routing + flags + the
 * source-bound SLO math, driven through the REAL {@link RemoteSessionHeartbeat} brain. A controllable
 * {@link StubStore} is the seam: it lets each test fix the store-recorded {@code revoked_at} / liveness /
 * revoke outcome deterministically, so latency, clock-skew, fail-closed and feed-drop paths are asserted
 * exactly. The real revoke→revoked_at coupling against Postgres is the {@code RevocationSloPostgresIT}.
 */
class RemoteSessionRevocationReconcilerTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");
    private static final Duration MAX_HB_AGE = Duration.ofSeconds(30);

    private final StubStore store = new StubStore();
    private final InMemorySessionRegistry registry = new InMemorySessionRegistry();
    private final RemoteSessionRevocationReconciler reconciler = new RemoteSessionRevocationReconciler(
            store, new RemoteSessionHeartbeat(store, new RemoteSessionStateMachine(), MAX_HB_AGE));

    private void registerActive(String sessionId, String jti) {
        registry.put(new RemoteSessionHeartbeat.SessionSnapshot(
                sessionId, jti, RemoteSessionState.ACTIVE, 0L, NOW)); // lastFreshAt=NOW ⇒ no heartbeat-timeout
    }

    private TokenRevocationFeed.RevocationEvent event(String jti, Instant revokedAt) {
        return new TokenRevocationFeed.RevocationEvent(
                jti, revokedAt, "OPERATOR_ABORT", "req-1", "sess", "operator:1", TokenRevocationFeed.Severity.URGENT);
    }

    @Test
    void pushKillsOwnerSessionWithSourceBoundLatency() {
        registerActive("s1", "jti-1");
        store.liveness.put("jti-1", TokenLifecycleStore.TokenLiveCheckResult.REVOKED);
        store.revokedAt.put("jti-1", NOW.minusMillis(1200)); // the store's recorded t0

        List<RemoteSessionRevocationReconciler.ReconcileOutcome> out =
                reconciler.onRevocation(event("jti-1", NOW.minusMillis(1100)), registry, NOW);

        assertEquals(1, out.size());
        var o = out.get(0);
        assertTrue(o.killed());
        assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_REVOKED, o.reason());
        assertEquals(RemoteSessionRevocationReconciler.Trigger.PUSH, o.trigger());
        assertEquals(1200L, o.latencyMillis()); // anchored on the STORE revoked_at, not the event clock
        assertFalse(o.negativeLatency());
        assertFalse(o.storeUnavailable());
        assertTrue(store.revoked.contains("jti-1")); // the authoritative revoke was applied
    }

    @Test
    void pushWithNoLocalOwnerReturnsEmpty() {
        // another replica owns the session; this one has nothing to kill (normal — not an error).
        store.liveness.put("jti-x", TokenLifecycleStore.TokenLiveCheckResult.REVOKED);
        store.revokedAt.put("jti-x", NOW.minusMillis(10));
        assertTrue(reconciler.onRevocation(event("jti-x", NOW), registry, NOW).isEmpty());
        assertTrue(store.revoked.contains("jti-x")); // still applies the revoke (idempotent, authoritative)
    }

    @Test
    void pushOwnershipConflictKillsAllAndFlags() {
        // single-owner assumption violated: two local ACTIVE sessions on one jti → kill both, flag conflict.
        registerActive("s1", "dup");
        registerActive("s2", "dup");
        store.liveness.put("dup", TokenLifecycleStore.TokenLiveCheckResult.REVOKED);
        store.revokedAt.put("dup", NOW.minusMillis(50));

        var out = reconciler.onRevocation(event("dup", NOW), registry, NOW);
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(o -> o.killed() && o.ownershipConflict()));
    }

    @Test
    void pushMeasuresEventVsStoreClockSkew() {
        registerActive("s1", "jti-skew");
        store.liveness.put("jti-skew", TokenLifecycleStore.TokenLiveCheckResult.REVOKED);
        store.revokedAt.put("jti-skew", NOW.minusMillis(800));        // store t0
        var out = reconciler.onRevocation(event("jti-skew", NOW.minusMillis(500)), registry, NOW); // event t0 differs by 300ms
        assertEquals(300L, out.get(0).eventDbSkewMillis());
    }

    @Test
    void pushNegativeLatencyIsFlaggedNotCounted() {
        registerActive("s1", "jti-future");
        store.liveness.put("jti-future", TokenLifecycleStore.TokenLiveCheckResult.REVOKED);
        store.revokedAt.put("jti-future", NOW.plusMillis(500)); // t0 AFTER now ⇒ clock glitch
        var o = reconciler.onRevocation(event("jti-future", NOW), registry, NOW).get(0);
        assertTrue(o.killed());                 // still killed (fail-closed)
        assertTrue(o.negativeLatency());        // but the sample is unreliable
        assertEquals(0L, o.latencyMillis());    // clamped, never negative
    }

    @Test
    void pushStoreUnavailableStillKillsFailClosed() {
        registerActive("s1", "jti-part");
        store.revokeOutcome = TokenLifecycleStore.MutationOutcome.STORE_UNAVAILABLE;
        store.liveness.put("jti-part", TokenLifecycleStore.TokenLiveCheckResult.STORE_UNAVAILABLE);
        var o = reconciler.onRevocation(event("jti-part", NOW), registry, NOW).get(0);
        assertTrue(o.killed());                  // partition ⇒ kill anyway (criterion #7)
        assertTrue(o.storeUnavailable());        // excluded from the revocation P95
        assertEquals(RemoteSessionStateMachine.KillReason.STORE_UNAVAILABLE, o.reason());
    }

    @Test
    void pollBackstopKillsFeedDroppedRevocation() {
        // the push event was dropped: the session is still ACTIVE locally but its token is REVOKED in the store.
        registerActive("s1", "jti-drop");
        store.liveness.put("jti-drop", TokenLifecycleStore.TokenLiveCheckResult.REVOKED);
        store.revokedAt.put("jti-drop", NOW.minusMillis(2000));
        var out = reconciler.pollReconcile(registry, NOW);
        assertEquals(1, out.size());
        var o = out.get(0);
        assertTrue(o.killed());
        assertTrue(o.feedDropRecovery());                                   // proves criterion #7 backstop
        assertEquals(RemoteSessionRevocationReconciler.Trigger.POLL, o.trigger());
        assertEquals(2000L, o.latencyMillis());                            // still source-bound on the poll path
    }

    @Test
    void pollLeavesLiveSessionUntouched() {
        registerActive("s1", "jti-live");
        store.liveness.put("jti-live", TokenLifecycleStore.TokenLiveCheckResult.LIVE);
        var out = reconciler.pollReconcile(registry, NOW);
        assertEquals(1, out.size());
        assertFalse(out.get(0).killed());
        assertFalse(out.get(0).feedDropRecovery());
    }

    @Test
    void pollDetectsOwnershipConflict() {
        registerActive("s1", "dup");
        registerActive("s2", "dup");
        store.liveness.put("dup", TokenLifecycleStore.TokenLiveCheckResult.REVOKED);
        store.revokedAt.put("dup", NOW.minusMillis(10));
        var out = reconciler.pollReconcile(registry, NOW);
        assertEquals(2, out.size());
        assertTrue(out.stream().allMatch(o -> o.killed() && o.ownershipConflict()));
    }

    /** Controllable {@link TokenLifecycleStore} test double — every read is fixed by the test. */
    private static final class StubStore implements TokenLifecycleStore {
        final Map<String, TokenLiveCheckResult> liveness = new HashMap<>();
        final Map<String, Instant> revokedAt = new HashMap<>();
        final List<String> revoked = new ArrayList<>();
        MutationOutcome revokeOutcome = MutationOutcome.UPDATED;

        @Override
        public ConsumeOutcome consume(String jti, Instant expiresAt, Instant now) {
            return ConsumeOutcome.ACCEPTED; // unused by the reconciler
        }

        @Override
        public MutationOutcome revoke(String jti) {
            revoked.add(jti);
            return revokeOutcome;
        }

        @Override
        public MutationOutcome expire(String jti) {
            return MutationOutcome.NOOP;
        }

        @Override
        public TokenLiveCheckResult isTokenLive(String jti, Instant now) {
            return liveness.getOrDefault(jti, TokenLiveCheckResult.NOT_FOUND);
        }

        @Override
        public Optional<Instant> revokedAt(String jti) {
            return Optional.ofNullable(revokedAt.get(jti));
        }
    }
}

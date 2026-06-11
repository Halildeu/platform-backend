package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B2.2a — heartbeat orchestrator tests (Codex 019eb54b criteria #2/#4/#5/#6 + REVISE absorb).
 * In-memory store, deterministic clock — no live Redis/scheduling (B2.2b).
 */
class RemoteSessionHeartbeatTest {

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant EXP = T0.plus(Duration.ofHours(1));
    private static final Duration MAX_AGE = Duration.ofSeconds(30);

    private final InMemoryTokenLifecycleStore store = new InMemoryTokenLifecycleStore();
    private final RemoteSessionHeartbeat hb =
            new RemoteSessionHeartbeat(store, new RemoteSessionStateMachine(), MAX_AGE);

    private static RemoteSessionHeartbeat.PreconditionSample healthy(Instant revokedAt) {
        return new RemoteSessionHeartbeat.PreconditionSample(true, true, true, true, true, revokedAt);
    }

    private static RemoteSessionHeartbeat.SessionSnapshot active(String jti, long seq, Instant lastFresh) {
        return new RemoteSessionHeartbeat.SessionSnapshot("sess", jti, RemoteSessionState.ACTIVE, seq, lastFresh);
    }

    @Test
    void healthyActiveSessionStaysActiveAndIsApplied() {
        store.consume("jti-1", EXP, T0);
        var d = hb.evaluate(active("jti-1", 10L, T0), healthy(null), 11L, T0.plusSeconds(5));
        assertEquals(RemoteSessionState.ACTIVE, d.target());
        assertFalse(d.kill());
        assertTrue(d.applied());
    }

    @Test
    void revokedTokenKillsWithMeasuredLatency() {
        store.consume("jti-2", EXP, T0);
        store.revoke("jti-2"); // t0 = T0
        Instant t3 = T0.plus(Duration.ofMillis(1200));
        var d = hb.evaluate(active("jti-2", 5L, T0), healthy(T0), 6L, t3);
        assertEquals(RemoteSessionState.ABORTED, d.target());
        assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_REVOKED, d.reason());
        assertTrue(d.kill());
        assertEquals(1200L, d.latencyMillis());
        assertFalse(d.clockSkew());
    }

    @Test
    void staleOrOutOfOrderSampleIsNotApplied() {
        store.consume("jti-3", EXP, T0);
        store.revoke("jti-3");
        // sampleSeq 20 == lastApplied 20 → stale; must NOT apply
        var d = hb.evaluate(active("jti-3", 20L, T0.plusSeconds(1)), healthy(T0), 20L, T0.plusSeconds(1));
        assertFalse(d.applied());
        assertFalse(d.kill());
        assertEquals(RemoteSessionState.ACTIVE, d.target());
    }

    @Test
    void storePartitionKillsWithDistinctStoreUnavailableReason() {
        store.consume("jti-4", EXP, T0);
        store.setAvailable(false); // criterion #7 + Codex absorb: distinct reason, NOT TOKEN_REVOKED
        var d = hb.evaluate(active("jti-4", 1L, T0), healthy(null), 2L, T0.plusSeconds(1));
        assertTrue(d.kill());
        assertEquals(RemoteSessionState.ABORTED, d.target());
        assertEquals(RemoteSessionStateMachine.KillReason.STORE_UNAVAILABLE, d.reason());
    }

    @Test
    void expiredTokenKillsWithDistinctExpiredReason() {
        store.consume("jti-5", EXP, T0);
        // lastFreshAt close to now (no heartbeat-timeout) but the token is past its TTL → TOKEN_EXPIRED
        var d = hb.evaluate(active("jti-5", 0L, EXP), healthy(null), 1L, EXP.plusSeconds(1));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_EXPIRED, d.reason());
    }

    @Test
    void heartbeatTimeoutKillsLiveSessionEvenWithoutAFreshSample() {
        store.consume("jti-6", EXP, T0);
        // lastFreshAt is 31s old (> maxHeartbeatAge 30s) → seq-independent kill
        var snap = active("jti-6", 100L, T0);
        var d = hb.evaluate(snap, healthy(null), 50L /* stale seq */, T0.plusSeconds(31));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.HEARTBEAT_TIMEOUT, d.reason());
        assertTrue(d.applied());
    }

    @Test
    void clockSkewIsFlaggedNotSilentlyZeroed() {
        store.consume("jti-7", EXP, T0);
        store.revoke("jti-7");
        // now (T0) is BEFORE revokedAt (T0+5s) → out-of-order/skew
        var d = hb.evaluate(active("jti-7", 1L, T0), healthy(T0.plusSeconds(5)), 2L, T0);
        assertTrue(d.kill());
        assertTrue(d.clockSkew());
        assertEquals(0L, d.latencyMillis()); // clamped, not negative
    }

    @Test
    void malformedHeartbeatForLiveSessionFailsClosed() {
        var d = hb.evaluate(active("jti-8", 0L, T0), null, 1L, T0.plusSeconds(1));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.VISIBILITY_LOSS, d.reason());
    }

    @Test
    void nonActiveSessionIsNotKilledByHeartbeat() {
        var snap = new RemoteSessionHeartbeat.SessionSnapshot(
                "sess", "jti-9", RemoteSessionState.RECORDING_READY, 0L, T0);
        var d = hb.evaluate(snap, healthy(null), 1L, T0.plusSeconds(120));
        assertFalse(d.kill());
        assertEquals(RemoteSessionState.RECORDING_READY, d.target());
    }
}

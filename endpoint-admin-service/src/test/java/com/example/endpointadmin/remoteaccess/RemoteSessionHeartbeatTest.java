package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B2.2a — heartbeat orchestrator tests (Codex 019eb54b criteria #2/#4/#5/#6 + REVISE absorb)
 * + B1.1c cert-binding enforcement (presented-vs-bound, check-list #1). In-memory store, deterministic
 * clock — no live Redis/scheduling (B2.2b).
 */
class RemoteSessionHeartbeatTest {

    private static final Instant T0 = Instant.parse("2026-06-11T10:00:00Z");
    private static final Instant EXP = T0.plus(Duration.ofHours(1));
    private static final Duration MAX_AGE = Duration.ofSeconds(30);

    /** Valid 64-hex SHA-256 thumbprints (the binding is hex-normalized, so case is irrelevant). */
    private static final String TP_A = "ab".repeat(32);
    private static final String TP_B = "cd".repeat(32);

    private final InMemoryTokenLifecycleStore store = new InMemoryTokenLifecycleStore();
    /** Production-target policy: every token must be cert-bound (fail-closed default). */
    private final RemoteSessionHeartbeat hb = new RemoteSessionHeartbeat(
            store, new RemoteSessionStateMachine(), MAX_AGE, CertBindingGuard.Policy.REQUIRE_BOUND);
    /** Migration-window policy: a legacy-unbound token may stay live (explicitly flagged). */
    private final RemoteSessionHeartbeat hbLegacyAllow = new RemoteSessionHeartbeat(
            store, new RemoteSessionStateMachine(), MAX_AGE, CertBindingGuard.Policy.ALLOW_LEGACY_UNBOUND);

    /**
     * Token-backstop sample (cert-unsampled) — these legacy B2 cases assert the token/visibility
     * machinery in isolation, exactly the view the revocation reconciler has (no transport layer).
     */
    private static RemoteSessionHeartbeat.PreconditionSample healthy(Instant revokedAt) {
        return RemoteSessionHeartbeat.PreconditionSample.certUnsampled(true, true, true, true, true, revokedAt);
    }

    /** Cert-sampling sample (the live heartbeat path) — the presented thumbprint IS enforced. */
    private static RemoteSessionHeartbeat.PreconditionSample presenting(String thumbprint) {
        return RemoteSessionHeartbeat.PreconditionSample.withCert(true, true, true, true, true, thumbprint, null);
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

    // ---- B1.1c: presented-vs-bound cert enforcement (Codex check-list #1) ----

    @Test
    void boundTokenWithMatchingPresentedThumbprintStaysActive() {
        store.consume("jti-c1", EXP, T0, TP_A); // pinned at consume (B1.1a)
        var d = hb.evaluate(active("jti-c1", 1L, T0), presenting(TP_A), 2L, T0.plusSeconds(5));
        assertFalse(d.kill());
        assertEquals(RemoteSessionState.ACTIVE, d.target());
    }

    @Test
    void boundTokenWithMismatchedPresentedThumbprintKills() {
        // check-list #1: a bound token presented under a DIFFERENT cert = possible token theft → kill
        store.consume("jti-c2", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-c2", 1L, T0), presenting(TP_B), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionState.FAILED_AGENT_ATTESTATION, d.target());
        assertEquals(RemoteSessionStateMachine.KillReason.CERT_BINDING_MISMATCH, d.reason());
    }

    @Test
    void boundTokenWithMissingPresentedThumbprintKills() {
        // check-list #1: blank/null presented on a BOUND token is fail-closed regardless of the flag —
        // under BOTH policies.
        store.consume("jti-c3", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-c3", 1L, T0), presenting(null), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.CERT_PRESENTED_MISSING, d.reason());

        var dAllow = hbLegacyAllow.evaluate(active("jti-c3", 1L, T0), presenting(" "), 2L, T0.plusSeconds(5));
        assertTrue(dAllow.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.CERT_PRESENTED_MISSING, dAllow.reason());
    }

    @Test
    void boundTokenMatchIsHexCaseInsensitive() {
        // the binding compares decoded bytes (B1.1a) — an uppercase presented form still matches
        store.consume("jti-c4", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-c4", 1L, T0), presenting(TP_A.toUpperCase()), 2L, T0.plusSeconds(5));
        assertFalse(d.kill());
    }

    @Test
    void legacyUnboundTokenUnderRequireBoundPolicyKills() {
        // check-list #3 (mid-session shape): an unbound token may not STAY active without the explicit
        // allow flag — covers the migration flag being flipped off while a legacy session is live.
        store.consume("jti-c5", EXP, T0); // legacy 3-arg consume — unbound
        var d = hb.evaluate(active("jti-c5", 1L, T0), presenting(TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionState.FAILED_AGENT_ATTESTATION, d.target());
        assertEquals(RemoteSessionStateMachine.KillReason.CERT_UNBOUND_REJECTED, d.reason());
    }

    @Test
    void legacyUnboundTokenUnderAllowPolicyStaysActive() {
        store.consume("jti-c6", EXP, T0); // unbound
        var d = hbLegacyAllow.evaluate(active("jti-c6", 1L, T0), presenting(null), 2L, T0.plusSeconds(5));
        assertFalse(d.kill());
        assertEquals(RemoteSessionState.ACTIVE, d.target());
    }

    @Test
    void certUnsampledInstrumentNeverProducesCertKills() {
        // the token-backstop reconciler has no transport view — a healthy BOUND session must survive its
        // sweep even under REQUIRE_BOUND (cert enforcement belongs to the cert-sampling live heartbeat).
        store.consume("jti-c7", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-c7", 1L, T0), healthy(null), 2L, T0.plusSeconds(5));
        assertFalse(d.kill());
        assertEquals(RemoteSessionState.ACTIVE, d.target());
    }

    @Test
    void storePartitionReportsStoreUnavailableNotACertReason() {
        // the binding truth is store-derived: with the store down, BOTH tokenBound and certBound are
        // unknowable — the kill must surface as STORE_UNAVAILABLE (token precedence), never as a cert
        // mismatch (audit mislabel guard; same doctrine as the B2.2a partition-vs-revoke distinction).
        store.consume("jti-c8", EXP, T0, TP_A);
        store.setAvailable(false);
        var d = hb.evaluate(active("jti-c8", 1L, T0), presenting(TP_B), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.STORE_UNAVAILABLE, d.reason());
    }

    @Test
    void revokedTokenReportsTokenReasonEvenWithCertMismatch() {
        // token loss precedes cert loss in the guarantee order — root cause stays the revocation
        store.consume("jti-c9", EXP, T0, TP_A);
        store.revoke("jti-c9");
        var d = hb.evaluate(active("jti-c9", 1L, T0), presenting(TP_B), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.TOKEN_REVOKED, d.reason());
    }
}

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

    /** Attestation fixture (B1.3b): the expected builder + SLSA policy the verifier is configured for. */
    private static final String BUILDER = "trusted-builder@slsa";
    private static final String POLICY = "expected-slsa-policy-hash";
    private static final String DIGEST = "agent-binary-sha256";

    private final InMemoryTokenLifecycleStore store = new InMemoryTokenLifecycleStore();
    /** A live attestation verifier (so a cert-sampling sample's {@code agentAttestation} is COMPUTED). */
    private final InMemoryAttestationVerifier attest = new InMemoryAttestationVerifier(BUILDER, POLICY);
    /** Production-target policy: every token must be cert-bound (fail-closed default). */
    private final RemoteSessionHeartbeat hb = new RemoteSessionHeartbeat(
            store, new RemoteSessionStateMachine(), MAX_AGE, CertBindingGuard.Policy.REQUIRE_BOUND,
            (c, n) -> CertTrustEvaluator.TrustDecision.ALLOW, attest);
    /** Migration-window policy: a legacy-unbound token may stay live (explicitly flagged). */
    private final RemoteSessionHeartbeat hbLegacyAllow = new RemoteSessionHeartbeat(
            store, new RemoteSessionStateMachine(), MAX_AGE, CertBindingGuard.Policy.ALLOW_LEGACY_UNBOUND,
            (c, n) -> CertTrustEvaluator.TrustDecision.ALLOW, attest);

    /** A complete, correctly-signed provenance for the expected builder + policy → verifier returns VERIFIED. */
    private static AttestationEvidence goodEvidence() {
        return evidence(DIGEST, BUILDER, POLICY);
    }

    /** Build a correctly-signed evidence for the given fields (mismatching builder/policy still verify-sign). */
    private static AttestationEvidence evidence(String digest, String builder, String policy) {
        return new AttestationEvidence(
                digest, builder, policy, InMemoryAttestationVerifier.expectedSignature(digest, builder, policy));
    }

    /**
     * Token-backstop sample (cert-unsampled) — these legacy B2 cases assert the token/visibility
     * machinery in isolation, exactly the view the revocation reconciler has (no transport layer).
     */
    private static RemoteSessionHeartbeat.PreconditionSample healthy(Instant revokedAt) {
        return RemoteSessionHeartbeat.PreconditionSample.certUnsampled(true, true, true, true, true, revokedAt);
    }

    /** Cert-sampling sample (the live heartbeat path) — presented thumbprint + GOOD attestation enforced. */
    private static RemoteSessionHeartbeat.PreconditionSample presenting(String thumbprint) {
        return presentingWith(goodEvidence(), thumbprint);
    }

    /** Cert-sampling sample carrying a specific provenance (B1.3b attestation cases). */
    private static RemoteSessionHeartbeat.PreconditionSample presentingWith(
            AttestationEvidence ev, String thumbprint) {
        return RemoteSessionHeartbeat.PreconditionSample.withCert(true, true, true, ev, true, thumbprint, null);
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

    // ---- B1.3b: heartbeat agent-attestation enforcement (provenance computed under certSampled) ----

    @Test
    void verifiedAttestationOnABoundSessionStaysActive() {
        // complete + trusted-builder + right-policy + signed provenance → agentAttestation computed true
        store.consume("jti-a1", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-a1", 1L, T0), presentingWith(goodEvidence(), TP_A), 2L, T0.plusSeconds(5));
        assertFalse(d.kill());
        assertEquals(RemoteSessionState.ACTIVE, d.target());
    }

    @Test
    void missingAttestationKillsWithAttestationMissing() {
        // a cert-sampling heartbeat with NO presented provenance is fail-closed (not implicitly trusted)
        store.consume("jti-a2", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-a2", 1L, T0), presentingWith(null, TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionState.FAILED_AGENT_ATTESTATION, d.target());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_MISSING, d.reason());
    }

    @Test
    void incompleteAttestationKillsWithAttestationMissing() {
        store.consume("jti-a3", EXP, T0, TP_A);
        var incomplete = new AttestationEvidence("", BUILDER, POLICY, "sig"); // blank digest → not complete
        var d = hb.evaluate(active("jti-a3", 1L, T0), presentingWith(incomplete, TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_MISSING, d.reason());
    }

    @Test
    void untrustedBuilderKillsWithAttestationUntrustedBuilder() {
        store.consume("jti-a4", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-a4", 1L, T0),
                presentingWith(evidence(DIGEST, "evil-builder", POLICY), TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_UNTRUSTED_BUILDER, d.reason());
    }

    @Test
    void midSessionBuilderRevocationKillsContinuously() {
        // a previously-VERIFIED session whose builder is revoked mid-session dies on its NEXT heartbeat
        store.consume("jti-a5", EXP, T0, TP_A);
        assertFalse(hb.evaluate(active("jti-a5", 1L, T0), presenting(TP_A), 2L, T0.plusSeconds(5)).kill());
        attest.revokeBuilder(BUILDER); // compromised-builder disclosure
        var d = hb.evaluate(active("jti-a5", 2L, T0), presenting(TP_A), 3L, T0.plusSeconds(10));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_UNTRUSTED_BUILDER, d.reason());
    }

    @Test
    void policyMismatchKillsWithAttestationPolicyMismatch() {
        store.consume("jti-a6", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-a6", 1L, T0),
                presentingWith(evidence(DIGEST, BUILDER, "other-policy"), TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_POLICY_MISMATCH, d.reason());
    }

    @Test
    void forgedSignatureKillsWithAttestationSigInvalid() {
        // complete + trusted builder + right policy, but the predicate signature does not verify
        store.consume("jti-a7", EXP, T0, TP_A);
        var forged = new AttestationEvidence(DIGEST, BUILDER, POLICY, "forged-signature");
        var d = hb.evaluate(active("jti-a7", 1L, T0), presentingWith(forged, TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_SIG_INVALID, d.reason());
    }

    @Test
    void attestationLossPrecedesTokenLoss() {
        // guarantee order: ATTESTATION (#2) is reported BEFORE TOKEN (#6) — a revoked token AND bad
        // provenance surfaces the attestation cause (the higher-precedence failure).
        store.consume("jti-a8", EXP, T0, TP_A);
        store.revoke("jti-a8");
        var d = hb.evaluate(active("jti-a8", 1L, T0), presentingWith(null, TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_MISSING, d.reason());
    }

    @Test
    void attestationLossPrecedesStoreUnavailable() {
        // Codex 019eb6d2 #4 boundary: attestation (#2) is COMPUTED from the agent's presented evidence —
        // independent of the token store — so a missing-provenance session is killed as ATTESTATION_MISSING
        // even when the token store is ALSO partitioned. The store-partition→STORE_UNAVAILABLE doctrine
        // guards the store-DERIVED token/binding guarantees (so a partition isn't mislabeled a revoke/
        // mismatch); it does NOT mask a genuine, transport-observed attestation failure that ranks higher.
        store.consume("jti-a11", EXP, T0, TP_A);
        store.setAvailable(false);
        var d = hb.evaluate(active("jti-a11", 1L, T0), presentingWith(null, TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_MISSING, d.reason());
    }

    @Test
    void throwingVerifierIsFailClosedMissing() {
        // Codex 019eb6d2 #1: a verifier that THROWS (a future B1.4 Sigstore/OCSP transport error) must not
        // bubble out of evaluate() and leave the session un-killed — it is coerced to MISSING (fail-closed).
        var hbThrows = new RemoteSessionHeartbeat(
                store, new RemoteSessionStateMachine(), MAX_AGE, CertBindingGuard.Policy.REQUIRE_BOUND,
                (c, n) -> CertTrustEvaluator.TrustDecision.ALLOW,
                (e, n) -> { throw new IllegalStateException("attestation backend down"); });
        store.consume("jti-a12", EXP, T0, TP_A);
        var d = hbThrows.evaluate(active("jti-a12", 1L, T0), presenting(TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_MISSING, d.reason());
    }

    @Test
    void certUnsampledInstrumentNeverAttestationKills() {
        // the token-backstop reconciler has no presented provenance — even though the live verifier would
        // reject (no evidence), a certUnsampled sweep passes the asserted boolean through and stays ACTIVE.
        store.consume("jti-a9", EXP, T0, TP_A);
        var d = hb.evaluate(active("jti-a9", 1L, T0), healthy(null), 2L, T0.plusSeconds(5));
        assertFalse(d.kill());
        assertEquals(RemoteSessionState.ACTIVE, d.target());
    }

    @Test
    void nullVerifierIsFailClosedDenyAll() {
        // an enabled runtime with NO configured attestation verifier (null) must refuse every cert-sampling
        // session — the heartbeat coerces null to a deny-all (MISSING) verifier (D10 must supply the policy).
        var hbNoVerifier = new RemoteSessionHeartbeat(
                store, new RemoteSessionStateMachine(), MAX_AGE, CertBindingGuard.Policy.REQUIRE_BOUND,
                (c, n) -> CertTrustEvaluator.TrustDecision.ALLOW, null);
        store.consume("jti-a10", EXP, T0, TP_A);
        var d = hbNoVerifier.evaluate(active("jti-a10", 1L, T0), presenting(TP_A), 2L, T0.plusSeconds(5));
        assertTrue(d.kill());
        assertEquals(RemoteSessionStateMachine.KillReason.ATTESTATION_MISSING, d.reason());
    }
}

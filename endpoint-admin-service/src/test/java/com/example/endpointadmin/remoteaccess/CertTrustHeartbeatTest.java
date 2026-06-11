package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.CertTrustEvaluator.TrustDecision;
import com.example.endpointadmin.remoteaccess.RemoteSessionHeartbeat.PreconditionSample;
import com.example.endpointadmin.remoteaccess.RemoteSessionHeartbeat.SessionSnapshot;
import com.example.endpointadmin.remoteaccess.RemoteSessionStateMachine.KillReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.2 — heartbeat cert-TRUST enforcement: a cert-sampling heartbeat kills a live session when the
 * presented cert is revoked / expired / untrusted / unknown / stale (refined KillReason), while the
 * token-backstop reconciler path (certUnsampled) NEVER trust-kills a guarantee it can't observe.
 */
class CertTrustHeartbeatTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");
    private static final Duration MAX_HB_AGE = Duration.ofSeconds(30);
    private static final Duration TRUST_MAX_AGE = Duration.ofMinutes(10);
    private static final String BOUND_TP = "aa".repeat(32); // a valid 64-hex thumbprint

    private final InMemoryTokenLifecycleStore store = new InMemoryTokenLifecycleStore();
    private final InMemoryCertTrustEvaluator trust = new InMemoryCertTrustEvaluator(TRUST_MAX_AGE);
    private final RemoteSessionHeartbeat hb = new RemoteSessionHeartbeat(
            store, new RemoteSessionStateMachine(), MAX_HB_AGE, CertBindingGuard.Policy.REQUIRE_BOUND, trust);

    @BeforeEach
    void seed() {
        // a cert-bound, live token; the presented cert matches the binding so only TRUST varies per test.
        store.consume("jti-1", NOW.plus(Duration.ofHours(1)), NOW, BOUND_TP);
    }

    private static SessionSnapshot active() {
        return new SessionSnapshot("s1", "jti-1", RemoteSessionState.ACTIVE, 0L, NOW);
    }

    private static PreconditionSample sampledWith(String presented) {
        return PreconditionSample.withCert(true, true, true, true, true, presented, null);
    }

    @Test
    void freshAllowCertStaysActive() {
        trust.put(BOUND_TP, TrustDecision.ALLOW, NOW);
        assertFalse(hb.evaluate(active(), sampledWith(BOUND_TP), 1, NOW).kill());
    }

    @Test
    void revokedCertKillsWithCertRevoked() {
        trust.put(BOUND_TP, TrustDecision.REVOKED, NOW);
        var d = hb.evaluate(active(), sampledWith(BOUND_TP), 1, NOW);
        assertTrue(d.kill());
        assertEquals(KillReason.CERT_REVOKED, d.reason());
    }

    @Test
    void expiredCertKillsWithCertExpired() {
        trust.put(BOUND_TP, TrustDecision.EXPIRED, NOW);
        assertEquals(KillReason.CERT_EXPIRED, hb.evaluate(active(), sampledWith(BOUND_TP), 1, NOW).reason());
    }

    @Test
    void staleRevocationCacheKillsWithCertStale() {
        trust.put(BOUND_TP, TrustDecision.ALLOW, NOW.minus(Duration.ofMinutes(11))); // stale
        var d = hb.evaluate(active(), sampledWith(BOUND_TP), 1, NOW);
        assertTrue(d.kill());
        assertEquals(KillReason.CERT_STALE, d.reason());
    }

    @Test
    void unreachableRevocationSourceKillsWithCertUnknown() {
        trust.put(BOUND_TP, TrustDecision.ALLOW, NOW);
        trust.setAvailable(false); // CRL/OCSP partition
        assertEquals(KillReason.CERT_UNKNOWN, hb.evaluate(active(), sampledWith(BOUND_TP), 1, NOW).reason());
    }

    @Test
    void untrustedChainKillsWithCertUntrusted() {
        trust.put(BOUND_TP, TrustDecision.NOT_TRUSTED, NOW);
        assertEquals(KillReason.CERT_UNTRUSTED, hb.evaluate(active(), sampledWith(BOUND_TP), 1, NOW).reason());
    }

    @Test
    void reconcilerCertUnsampledNeverTrustKillsAHealthyToken() {
        // even with a REVOKED cert in the trust cache, the token-backstop reconciler (certUnsampled) has no
        // transport view → it must NOT trust-kill; the live token is still LIVE so the session stays up.
        trust.put(BOUND_TP, TrustDecision.REVOKED, NOW);
        var unsampled = PreconditionSample.certUnsampled(true, true, true, true, true, null);
        assertFalse(hb.evaluate(active(), unsampled, 1, NOW).kill());
    }
}

package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.CertTrustEvaluator.TrustDecision;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 B1.2 — {@link InMemoryCertTrustEvaluator} fail-closed trust + freshness semantics. */
class CertTrustEvaluatorTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final InMemoryCertTrustEvaluator evaluator = new InMemoryCertTrustEvaluator(MAX_AGE);

    private static CertRef ref(String thumbprint) {
        return CertRef.ofThumbprint(thumbprint);
    }

    @Test
    void freshAllowIsValid() {
        evaluator.put("tp-1", TrustDecision.ALLOW, NOW);
        assertEquals(TrustDecision.ALLOW, evaluator.evaluate(ref("tp-1"), NOW));
        assertTrue(evaluator.evaluate(ref("tp-1"), NOW).isValid());
    }

    @Test
    void revokedExpiredUntrustedAreReturnedAndInvalid() {
        evaluator.put("r", TrustDecision.REVOKED, NOW);
        evaluator.put("e", TrustDecision.EXPIRED, NOW);
        evaluator.put("n", TrustDecision.NOT_TRUSTED, NOW);
        assertEquals(TrustDecision.REVOKED, evaluator.evaluate(ref("r"), NOW));
        assertEquals(TrustDecision.EXPIRED, evaluator.evaluate(ref("e"), NOW));
        assertEquals(TrustDecision.NOT_TRUSTED, evaluator.evaluate(ref("n"), NOW));
        assertFalse(evaluator.evaluate(ref("r"), NOW).isValid());
    }

    @Test
    void unseenCertIsUnknownFailClosed() {
        assertEquals(TrustDecision.UNKNOWN, evaluator.evaluate(ref("never-seen"), NOW));
    }

    @Test
    void partitionIsUnknownFailClosedEvenForAPreviouslyAllowedCert() {
        evaluator.put("tp", TrustDecision.ALLOW, NOW);
        evaluator.setAvailable(false); // CRL/OCSP unreachable
        assertEquals(TrustDecision.UNKNOWN, evaluator.evaluate(ref("tp"), NOW));
    }

    @Test
    void staleCacheFailsClosedWithNoGrace() {
        evaluator.put("tp", TrustDecision.ALLOW, NOW.minus(Duration.ofMinutes(11))); // older than MAX_AGE
        assertEquals(TrustDecision.STALE, evaluator.evaluate(ref("tp"), NOW));
    }

    @Test
    void withinFreshnessWindowStillAllow() {
        evaluator.put("tp", TrustDecision.ALLOW, NOW.minus(Duration.ofMinutes(9))); // inside MAX_AGE
        assertEquals(TrustDecision.ALLOW, evaluator.evaluate(ref("tp"), NOW));
    }

    @Test
    void nullOrAbsentCertIsNotTrusted() {
        assertEquals(TrustDecision.NOT_TRUSTED, evaluator.evaluate(null, NOW));
        assertEquals(TrustDecision.NOT_TRUSTED, evaluator.evaluate(CertRef.ofThumbprint(null), NOW));
        assertEquals(TrustDecision.NOT_TRUSTED, evaluator.evaluate(CertRef.ofThumbprint("  "), NOW));
    }

    @Test
    void onlyAllowIsValid() {
        for (TrustDecision d : TrustDecision.values()) {
            assertEquals(d == TrustDecision.ALLOW, d.isValid(), d.name());
        }
    }
}

package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.CertTrustEvaluatorFactory.EvaluatorType;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluatorFactory.RevocationMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.TrustAnchor;
import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.4a-3 — {@link CertTrustEvaluatorFactory} selection + the Codex 019eb6d9 blocking matrix
 * (enforced at construction = startup fail-fast).
 */
class CertTrustEvaluatorFactoryTest {

    private static final Duration MAX_AGE = Duration.ofHours(1);

    private static Set<TrustAnchor> rootAnchors() throws CertificateException {
        try (InputStream in = CertTrustEvaluatorFactoryTest.class
                .getResourceAsStream("/remoteaccess/pki/root-ca.pem")) {
            return TrustAnchorLoader.fromPemBundle(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (CertificateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void nullTypeDefaultsToInMemory() {
        var e = CertTrustEvaluatorFactory.create(null, null, Set.of(), false, false, MAX_AGE);
        assertInstanceOf(InMemoryCertTrustEvaluator.class, e);
    }

    @Test
    void inMemorySelectsTheModelledEvaluator() {
        var e = CertTrustEvaluatorFactory.create(EvaluatorType.IN_MEMORY, RevocationMode.DISABLED, null, false, false, MAX_AGE);
        assertInstanceOf(InMemoryCertTrustEvaluator.class, e);
    }

    @Test
    void realPkiWithNoAnchorsFailsFast() {
        var ex = assertThrows(IllegalStateException.class, () ->
                CertTrustEvaluatorFactory.create(EvaluatorType.REAL_PKI, RevocationMode.DISABLED, Set.of(), true, false, MAX_AGE));
        assertTrue(ex.getMessage().contains("trust anchors"), ex.getMessage());
    }

    @Test
    void realPkiWithDisabledRevocationAndNoOverrideIsForbidden() throws CertificateException {
        var ex = assertThrows(IllegalStateException.class, () ->
                CertTrustEvaluatorFactory.create(EvaluatorType.REAL_PKI, RevocationMode.DISABLED, rootAnchors(), false, false, MAX_AGE));
        assertTrue(ex.getMessage().contains("DISABLED is forbidden"), ex.getMessage());
    }

    @Test
    void realPkiWithDisabledRevocationAndTheTestOnlyOverrideBuildsThePkiEvaluator() throws CertificateException {
        var e = CertTrustEvaluatorFactory.create(EvaluatorType.REAL_PKI, RevocationMode.DISABLED, rootAnchors(), true, false, MAX_AGE);
        assertInstanceOf(CertPathTrustEvaluator.class, e);
    }

    @Test
    void realPkiWithCrlOrOcspIsRejectedUntilB14b() throws CertificateException {
        for (RevocationMode mode : new RevocationMode[] {RevocationMode.CRL, RevocationMode.OCSP}) {
            var ex = assertThrows(IllegalStateException.class, () ->
                    CertTrustEvaluatorFactory.create(EvaluatorType.REAL_PKI, mode, rootAnchors(), false, false, MAX_AGE));
            assertTrue(ex.getMessage().contains("not yet implemented"), mode + ": " + ex.getMessage());
        }
    }

    @Test
    void realPkiInsecureOverrideIsRefusedInAProductionLikeProfile() throws CertificateException {
        // Codex 019eb6d9: even with the test-only escape flag set, a production-like profile refuses it
        var ex = assertThrows(IllegalStateException.class, () -> CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.DISABLED, rootAnchors(), true, true, MAX_AGE));
        assertTrue(ex.getMessage().contains("production-like"), ex.getMessage());
    }
}

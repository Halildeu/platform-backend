package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.CertTrustEvaluatorFactory.EvaluatorType;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluatorFactory.RevocationMode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.4a-3 + B1.4b — {@link CertTrustEvaluatorFactory} selection + the Codex 019eb6d9 blocking
 * matrix (enforced at construction = startup fail-fast), now including the B1.4b REAL_PKI + CRL prod-legal
 * combination.
 */
class CertTrustEvaluatorFactoryTest {

    private static final Duration MAX_AGE = Duration.ofHours(1);
    private static final List<X509CRL> NO_CRLS = List.of();

    private static byte[] fx(String name) {
        try (InputStream in = CertTrustEvaluatorFactoryTest.class.getResourceAsStream("/remoteaccess/pki/" + name)) {
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("read " + name, e);
        }
    }

    private static Set<TrustAnchor> rootAnchors() throws GeneralSecurityException {
        return TrustAnchorLoader.fromPemBundle(new String(fx("root-ca.pem"), StandardCharsets.UTF_8));
    }

    private static List<X509CRL> crls() throws GeneralSecurityException {
        return List.of(X509ChainParser.parseCrl(fx("intermediate.crl")));
    }

    @Test
    void nullTypeDefaultsToInMemory() {
        var e = CertTrustEvaluatorFactory.create(null, null, Set.of(), NO_CRLS, false, false, MAX_AGE);
        assertInstanceOf(InMemoryCertTrustEvaluator.class, e);
    }

    @Test
    void inMemorySelectsTheModelledEvaluator() {
        var e = CertTrustEvaluatorFactory.create(
                EvaluatorType.IN_MEMORY, RevocationMode.DISABLED, null, NO_CRLS, false, false, MAX_AGE);
        assertInstanceOf(InMemoryCertTrustEvaluator.class, e);
    }

    @Test
    void realPkiWithNoAnchorsFailsFast() {
        var ex = assertThrows(IllegalStateException.class, () -> CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.DISABLED, Set.of(), NO_CRLS, true, false, MAX_AGE));
        assertTrue(ex.getMessage().contains("trust anchors"), ex.getMessage());
    }

    @Test
    void realPkiWithDisabledRevocationAndNoOverrideIsForbidden() throws GeneralSecurityException {
        var ex = assertThrows(IllegalStateException.class, () -> CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.DISABLED, rootAnchors(), NO_CRLS, false, false, MAX_AGE));
        assertTrue(ex.getMessage().contains("DISABLED is forbidden"), ex.getMessage());
    }

    @Test
    void realPkiWithDisabledRevocationAndTheTestOnlyOverrideBuildsThePkiEvaluator() throws GeneralSecurityException {
        var e = CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.DISABLED, rootAnchors(), NO_CRLS, true, false, MAX_AGE);
        assertInstanceOf(CertPathTrustEvaluator.class, e);
    }

    @Test
    void realPkiInsecureOverrideIsRefusedInAProductionLikeProfile() throws GeneralSecurityException {
        // Codex 019eb6d9: even with the test-only escape flag set, a production-like profile refuses it
        var ex = assertThrows(IllegalStateException.class, () -> CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.DISABLED, rootAnchors(), NO_CRLS, true, true, MAX_AGE));
        assertTrue(ex.getMessage().contains("production-like"), ex.getMessage());
    }

    // ---- B1.4b: REAL_PKI + CRL is a legal prod combo (CRLs mandatory) ----

    @Test
    void realPkiWithCrlAndConfiguredCrlsBuildsThePkiEvaluatorEvenInProd() throws GeneralSecurityException {
        // no insecure override + a production-like profile: REAL_PKI + CRL is legitimately allowed
        var e = CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.CRL, rootAnchors(), crls(), false, true, MAX_AGE);
        assertInstanceOf(CertPathTrustEvaluator.class, e);
    }

    @Test
    void realPkiWithCrlButNoConfiguredCrlsFailsFast() throws GeneralSecurityException {
        var ex = assertThrows(IllegalStateException.class, () -> CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.CRL, rootAnchors(), NO_CRLS, false, false, MAX_AGE));
        assertTrue(ex.getMessage().contains("requires configured CRLs"), ex.getMessage());
    }

    @Test
    void realPkiWithOcspIsRejectedUntilItHasALiveResponder() throws GeneralSecurityException {
        var ex = assertThrows(IllegalStateException.class, () -> CertTrustEvaluatorFactory.create(
                EvaluatorType.REAL_PKI, RevocationMode.OCSP, rootAnchors(), crls(), false, false, MAX_AGE));
        assertTrue(ex.getMessage().contains("OCSP is not yet implemented"), ex.getMessage());
    }
}

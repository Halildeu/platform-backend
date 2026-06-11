package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.CertTrustEvaluator.TrustDecision;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.cert.CertificateException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faz 22.6 B1.4a-2 — {@link CertPathTrustEvaluator} real JDK PKIX path validation against the committed
 * offline fixture corpus. Trust anchor = the fixture root; validated at a FIXED Instant (2035) so leaf-valid
 * (notAfter 2046) passes and leaf-expired (2020–2021) fails deterministically, independent of wall-clock.
 */
class CertPathTrustEvaluatorTest {

    /** A clock at which leaf-valid is inside its window and leaf-expired is well past its 2021 notAfter. */
    private static final Instant AT_2035 = Instant.parse("2035-01-01T00:00:00Z");

    private static byte[] fx(String name) {
        try (InputStream in = CertPathTrustEvaluatorTest.class.getResourceAsStream("/remoteaccess/pki/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("read " + name, e);
        }
    }

    private static Set<TrustAnchor> rootAnchors() throws CertificateException {
        return TrustAnchorLoader.load(List.of(fx("root-ca.pem")));
    }

    /** A presented cert whose leaf-first chain is the given fixtures. */
    private static CertRef presented(byte[]... chain) {
        return new CertRef("tp", "SHA-256", null, null, List.of(chain));
    }

    @Test
    void validChainToTheAnchorAllows() throws CertificateException {
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        var d = eval.evaluate(presented(fx("leaf-valid.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.ALLOW, d);
    }

    @Test
    void expiredLeafIsExpired() throws CertificateException {
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        var d = eval.evaluate(presented(fx("leaf-expired.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.EXPIRED, d);
    }

    @Test
    void chainToAnUnknownRootIsNotTrusted() throws CertificateException {
        // leaf-unknown is issued by a root NOT in the anchor set → no path builds
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        var d = eval.evaluate(presented(fx("leaf-unknown.pem")), AT_2035);
        assertEquals(TrustDecision.NOT_TRUSTED, d);
    }

    @Test
    void anIncompleteChainMissingTheIntermediateIsNotTrusted() throws CertificateException {
        // leaf-valid alone: its issuer is the intermediate, not the root anchor → no path
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        var d = eval.evaluate(presented(fx("leaf-valid.pem")), AT_2035);
        assertEquals(TrustDecision.NOT_TRUSTED, d);
    }

    @Test
    void emptyChainIsNotTrusted() throws CertificateException {
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        assertEquals(TrustDecision.NOT_TRUSTED, eval.evaluate(presented(), AT_2035));
    }

    @Test
    void noConfiguredAnchorsIsNotTrusted() {
        // even a structurally valid chain trusts nothing without a configured root
        var eval = new CertPathTrustEvaluator(Set.of(), false);
        var d = eval.evaluate(presented(fx("leaf-valid.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.NOT_TRUSTED, d);
    }

    @Test
    void aMalformedChainIsFailClosedNotTrusted() throws CertificateException {
        // a-2 tightening: the parse throws → the evaluator never proceeds (no partial-accept)
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        var d = eval.evaluate(presented("garbage".getBytes(StandardCharsets.UTF_8)), AT_2035);
        assertEquals(TrustDecision.NOT_TRUSTED, d);
    }

    @Test
    void nullCertOrNullNowIsNotTrusted() throws CertificateException {
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        assertEquals(TrustDecision.NOT_TRUSTED, eval.evaluate(null, AT_2035));
        assertEquals(TrustDecision.NOT_TRUSTED,
                eval.evaluate(presented(fx("leaf-valid.pem"), fx("intermediate-ca.pem")), null));
    }

    // ---- B1.4a-3: EKU enforcement (right chain, wrong purpose) ----

    @Test
    void aServerAuthLeafThatChainsValidlyIsRejectedAsWrongPurpose() throws CertificateException {
        // leaf-serverauth chains to the SAME trusted intermediate/root + is in-validity, but its EKU is
        // serverAuth (NOT clientAuth) → secure-by-default (2-arg ctor) rejects it (Codex 019eb6d9).
        var eval = new CertPathTrustEvaluator(rootAnchors(), false);
        var d = eval.evaluate(presented(fx("leaf-serverauth.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.NOT_TRUSTED, d);
    }

    @Test
    void theSameServerAuthLeafIsAllowedWhenClientAuthIsNotRequired() throws CertificateException {
        // the EKU enforcement is opt-out via the 3-arg ctor (requireClientAuth=false): the chain itself is valid
        var eval = new CertPathTrustEvaluator(rootAnchors(), false, false);
        var d = eval.evaluate(presented(fx("leaf-serverauth.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.ALLOW, d);
    }

    @Test
    void aClientAuthLeafStillAllowsUnderSecureByDefault() throws CertificateException {
        // the secure-by-default (clientAuth-required) path still ALLOWs a legitimate clientAuth leaf
        var eval = new CertPathTrustEvaluator(rootAnchors(), false); // requireClientAuth=true
        var d = eval.evaluate(presented(fx("leaf-valid.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.ALLOW, d);
    }

    // ---- B1.4b: offline CRL revocation ----

    private static List<X509CRL> crls() throws GeneralSecurityException {
        return List.of(X509ChainParser.parseCrl(fx("intermediate.crl")));
    }

    @Test
    void aRevokedLeafIsRevokedAgainstTheCrl() throws GeneralSecurityException {
        // leaf-revoked (serial 2000) is listed in intermediate.crl → REVOKED (revocation ON, CRL provided)
        var eval = new CertPathTrustEvaluator(rootAnchors(), true, true, crls());
        var d = eval.evaluate(presented(fx("leaf-revoked.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.REVOKED, d);
    }

    @Test
    void aValidLeafNotOnTheCrlStaysAllowed() throws GeneralSecurityException {
        // leaf-valid (serial 4096) is NOT on the CRL → revocation passes → ALLOW
        var eval = new CertPathTrustEvaluator(rootAnchors(), true, true, crls());
        var d = eval.evaluate(presented(fx("leaf-valid.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.ALLOW, d);
    }

    @Test
    void revocationEnabledWithNoCrlIsUnknownFailClosed() throws GeneralSecurityException {
        // revocation ON but NO CRL covers the cert → UNDETERMINED → UNKNOWN (fail-closed, no grace, NO_FALLBACK)
        var eval = new CertPathTrustEvaluator(rootAnchors(), true); // revocationEnabled=true, empty CRLs
        var d = eval.evaluate(presented(fx("leaf-valid.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.UNKNOWN, d);
    }

    @Test
    void aStaleCrlPastItsNextUpdateIsUnknownFailClosed() throws GeneralSecurityException {
        // Codex 019eb6d9: a CRL whose nextUpdate (2026) is BEFORE the validation Instant (2035) can no longer
        // prove non-revocation → UNDETERMINED → UNKNOWN (a stale revocation source is exactly the window an
        // attacker with a just-revoked cert wants; fail-closed, no grace — B1.2 doctrine).
        var staleCrl = List.of(X509ChainParser.parseCrl(fx("intermediate-stale.crl")));
        var eval = new CertPathTrustEvaluator(rootAnchors(), true, true, staleCrl);
        var d = eval.evaluate(presented(fx("leaf-valid.pem"), fx("intermediate-ca.pem")), AT_2035);
        assertEquals(TrustDecision.UNKNOWN, d);
    }
}

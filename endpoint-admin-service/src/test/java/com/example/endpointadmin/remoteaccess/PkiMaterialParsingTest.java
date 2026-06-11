package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.4a-1 — {@link X509ChainParser} + {@link TrustAnchorLoader} against the committed offline PKI
 * fixture corpus ({@code src/test/resources/remoteaccess/pki/}: a self-signed root → intermediate →
 * leaf-valid / leaf-expired, plus an unknown-root → leaf-unknown). This slice proves DETERMINISTIC parsing +
 * fail-closed handling; the PKIX path validation that uses them is B1.4a-2.
 */
class PkiMaterialParsingTest {

    private static byte[] fixture(String name) {
        try (InputStream in = PkiMaterialParsingTest.class.getResourceAsStream("/remoteaccess/pki/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing PKI fixture: " + name);
            }
            return in.readAllBytes();
        } catch (Exception e) {
            throw new IllegalStateException("failed to read fixture " + name, e);
        }
    }

    @Test
    void parsesEveryFixtureCertificateStructurally() throws CertificateException {
        // every committed fixture must parse as a valid X.509 cert with the expected subject CN
        assertSubject("root-ca.pem", "Agent Fleet Root CA");
        assertSubject("intermediate-ca.pem", "Agent Fleet Intermediate CA");
        assertSubject("leaf-valid.pem", "agent-001.fleet.acik");
        assertSubject("leaf-expired.pem", "agent-001.fleet.acik");
        assertSubject("unknown-root.pem", "Rogue Root CA");
        assertSubject("leaf-unknown.pem", "agent-001.fleet.acik");
    }

    private static void assertSubject(String fixture, String expectedCn) throws CertificateException {
        X509Certificate cert = X509ChainParser.parseCertificate(fixture(fixture));
        assertTrue(cert.getSubjectX500Principal().getName().contains("CN=" + expectedCn),
                fixture + " subject=" + cert.getSubjectX500Principal().getName());
    }

    @Test
    void parsesAnOrderedLeafFirstChain() throws CertificateException {
        List<X509Certificate> chain = X509ChainParser.parseChain(List.of(
                fixture("leaf-valid.pem"), fixture("intermediate-ca.pem"), fixture("root-ca.pem")));
        assertEquals(3, chain.size());
        assertTrue(chain.get(0).getSubjectX500Principal().getName().contains("agent-001"), "leaf first");
        assertTrue(chain.get(1).getSubjectX500Principal().getName().contains("Intermediate"), "intermediate second");
        assertTrue(chain.get(2).getSubjectX500Principal().getName().contains("Root"), "root last");
    }

    @Test
    void nullOrEmptyChainParsesToEmptyList() throws CertificateException {
        assertTrue(X509ChainParser.parseChain(null).isEmpty());
        assertTrue(X509ChainParser.parseChain(List.of()).isEmpty());
    }

    @Test
    void malformedOrEmptyCertIsFailClosed() {
        assertThrows(CertificateException.class, () -> X509ChainParser.parseCertificate(new byte[0]));
        assertThrows(CertificateException.class, () -> X509ChainParser.parseCertificate(null));
        assertThrows(CertificateException.class,
                () -> X509ChainParser.parseCertificate("-----BEGIN CERTIFICATE-----\nnot-base64\n-----END CERTIFICATE-----"
                        .getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void aMalformedEntryFailsTheWholeChain() {
        // fail-closed: one bad entry raises, never a silently-truncated partial chain
        assertThrows(CertificateException.class, () -> X509ChainParser.parseChain(List.of(
                fixture("leaf-valid.pem"), "garbage".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void loadsTheRootAsATrustAnchor() throws CertificateException {
        Set<TrustAnchor> anchors = TrustAnchorLoader.load(List.of(fixture("root-ca.pem")));
        assertEquals(1, anchors.size());
        TrustAnchor anchor = anchors.iterator().next();
        assertTrue(anchor.getTrustedCert().getSubjectX500Principal().getName().contains("Agent Fleet Root CA"));
    }

    @Test
    void noConfiguredAnchorsYieldsAnEmptySetNotTheJdkDefault() throws CertificateException {
        // fail-closed: no roots → trust nothing (NOT a fallback to the public-web cacerts)
        assertTrue(TrustAnchorLoader.load(null).isEmpty());
        assertTrue(TrustAnchorLoader.load(List.of()).isEmpty());
    }

    @Test
    void aMalformedAnchorIsFailClosed() {
        assertThrows(CertificateException.class,
                () -> TrustAnchorLoader.load(List.of("not-a-cert".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void loadsAMultiCertPemBundle() throws CertificateException {
        // B1.4a-3 config shape: a single PEM blob carrying several roots
        String bundle = new String(fixture("root-ca.pem"), StandardCharsets.UTF_8)
                + "\n" + new String(fixture("unknown-root.pem"), StandardCharsets.UTF_8);
        assertEquals(2, TrustAnchorLoader.fromPemBundle(bundle).size());
    }

    @Test
    void aBlankBundleYieldsAnEmptySet() throws CertificateException {
        // fail-closed: an unconfigured anchor bundle → empty (the REAL_PKI factory then fails fast)
        assertTrue(TrustAnchorLoader.fromPemBundle(null).isEmpty());
        assertTrue(TrustAnchorLoader.fromPemBundle("   ").isEmpty());
    }
}

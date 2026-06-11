package com.example.endpointadmin.remoteaccess;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Faz 22.6 B1.4a-1 — loads the configured agent-fleet trust anchors (root CA certificates) into JDK
 * {@link TrustAnchor}s, the trust roots the B1.4a {@code CertPathTrustEvaluator} validates a presented
 * chain against. Pure + JDK-only. Deterministic, offline.
 *
 * <p><b>Fail-closed:</b> an empty/blank anchor set yields an EMPTY {@link Set} — and a PKIX validation with
 * no trust anchors can build no path, so every presented chain is NOT_TRUSTED. That is the correct
 * fail-closed default: a runtime with no configured root of trust trusts nothing (it does NOT fall back to
 * the JDK default cacerts, which would trust the public web PKI — irrelevant + dangerous for an internal
 * agent fleet). A malformed anchor cert raises {@link CertificateException} rather than being skipped.
 */
public final class TrustAnchorLoader {

    /**
     * Load each DER/PEM-encoded root CA certificate as a name-constraint-free {@link TrustAnchor}. A
     * {@code null}/empty input yields an empty set (trust nothing). Never returns {@code null}.
     *
     * @throws CertificateException if any anchor is malformed / not an X.509 certificate
     */
    public static Set<TrustAnchor> load(Collection<byte[]> anchorCerts) throws CertificateException {
        Set<TrustAnchor> anchors = new LinkedHashSet<>();
        if (anchorCerts == null) {
            return anchors;
        }
        for (byte[] encoded : anchorCerts) {
            X509Certificate cert = X509ChainParser.parseCertificate(encoded);
            anchors.add(new TrustAnchor(cert, null)); // null name-constraints = no additional constraint
        }
        return anchors;
    }

    /**
     * Load a (possibly multi-cert) PEM bundle — the B1.4a-3 config shape
     * ({@code endpoint-admin.remote-access.cert-trust.trust-anchor-pem}) — into trust anchors. A
     * {@code null}/blank bundle yields an EMPTY set (the REAL_PKI factory then fails fast). Fail-closed:
     * a malformed bundle raises {@link CertificateException}.
     */
    public static Set<TrustAnchor> fromPemBundle(String pemBundle) throws CertificateException {
        Set<TrustAnchor> anchors = new LinkedHashSet<>();
        if (pemBundle == null || pemBundle.isBlank()) {
            return anchors;
        }
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs = cf.generateCertificates(
                new ByteArrayInputStream(pemBundle.getBytes(StandardCharsets.UTF_8)));
        for (Certificate c : certs) {
            anchors.add(new TrustAnchor((X509Certificate) c, null));
        }
        return anchors;
    }

    private TrustAnchorLoader() {
    }
}

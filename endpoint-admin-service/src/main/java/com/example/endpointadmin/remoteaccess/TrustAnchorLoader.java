package com.example.endpointadmin.remoteaccess;

import java.security.cert.CertificateException;
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

    private TrustAnchorLoader() {
    }
}

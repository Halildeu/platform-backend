package com.example.endpointadmin.remoteaccess;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Faz 22.6 B1.4a-1 — decodes the presented client-cert chain (DER or PEM bytes) into JDK
 * {@link X509Certificate} objects, the raw material the B1.4a {@code CertPathTrustEvaluator} (PKIX
 * path-build) consumes. Pure + JDK-only (no BouncyCastle): {@link CertificateFactory} auto-detects DER vs
 * PEM. This is the deterministic, offline parsing foundation; the path validation + trust-anchor + CRL/OCSP
 * are the B1.4a/B1.4b slices that compose it.
 *
 * <p><b>Fail-closed:</b> a {@code null}/empty chain yields an empty list (the caller treats "no chain" as
 * NOT_TRUSTED), and ANY malformed / non-X.509 entry raises {@link CertificateException} — the evaluator
 * catches it and fails the cert closed rather than proceeding on a half-parsed chain. No entry is silently
 * skipped.
 */
public final class X509ChainParser {

    private static final String X509 = "X.509";

    /**
     * Parse a single DER- or PEM-encoded certificate. Never returns {@code null}.
     *
     * @throws CertificateException if {@code encoded} is null/empty or not a valid X.509 certificate
     */
    public static X509Certificate parseCertificate(byte[] encoded) throws CertificateException {
        if (encoded == null || encoded.length == 0) {
            throw new CertificateException("empty certificate bytes");
        }
        CertificateFactory cf = CertificateFactory.getInstance(X509);
        // generateCertificate returns a java.security.cert.Certificate; for X.509 it is an X509Certificate.
        return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(encoded));
    }

    /**
     * Parse an ordered chain (leaf first, then issuing intermediates) of DER/PEM-encoded certs. A
     * {@code null}/empty input yields an empty list (no chain presented). Every entry MUST parse — a single
     * malformed entry raises {@link CertificateException} (fail-closed, no partial chain).
     */
    public static List<X509Certificate> parseChain(List<byte[]> encodedChain) throws CertificateException {
        if (encodedChain == null || encodedChain.isEmpty()) {
            return List.of();
        }
        List<X509Certificate> certs = new ArrayList<>(encodedChain.size());
        for (byte[] encoded : encodedChain) {
            certs.add(parseCertificate(encoded));
        }
        return List.copyOf(certs);
    }

    private X509ChainParser() {
    }
}

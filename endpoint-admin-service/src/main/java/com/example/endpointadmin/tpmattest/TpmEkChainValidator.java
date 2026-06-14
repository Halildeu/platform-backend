package com.example.endpointadmin.tpmattest;

import java.security.MessageDigest;
import java.security.cert.CertPathValidator;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

/**
 * Faz 22.3B (ADR-0039) gate-4 — verifier V2: validate that a device's TPM
 * Endorsement Key (EK) certificate chains to a trusted manufacturer root
 * (design §4, §10.5 T-7/T-10).
 *
 * <p><b>Build-time root pinning (T-10):</b> the manufacturer-root bundle is
 * supplied as actual certificates AND each is verified at construction against
 * the pinned SHA-256 set ({@code endpoint-admin.tpm-attest.manufacturer-root-sha256}).
 * A bundle cert whose fingerprint is not pinned is rejected — the trust anchor
 * set can never silently widen. Supports a current+next dual-root window (T-7).
 *
 * <p>Fail-closed: unknown/expired/untrusted EK chain → {@link TpmDenyCode#EK_UNTRUSTED}
 * via {@link EkChainException}. (Revocation/CRL/OCSP + ROCA-class denylist are
 * layered in a later slice; this is the chain + pin core.)
 */
public final class TpmEkChainValidator {

    /** Thrown on any EK-chain failure; maps to {@link TpmDenyCode#EK_UNTRUSTED}. */
    public static final class EkChainException extends Exception {
        public EkChainException(String message, Throwable cause) { super(message, cause); }
        public EkChainException(String message) { super(message); }
    }

    private final Set<TrustAnchor> trustAnchors;

    /**
     * @param pinnedRootSha256 lowercase-hex SHA-256 fingerprints of the allowed roots
     * @param rootBundle the actual manufacturer root certificates
     * @throws EkChainException if a bundle cert's fingerprint is not pinned (config fail-closed)
     */
    public TpmEkChainValidator(Set<String> pinnedRootSha256, List<X509Certificate> rootBundle)
            throws EkChainException {
        Set<String> pins = new HashSet<>();
        for (String p : pinnedRootSha256) {
            pins.add(p.toLowerCase().replace(":", "").trim());
        }
        Set<TrustAnchor> anchors = new HashSet<>();
        for (X509Certificate root : rootBundle) {
            String fp = sha256Hex(root);
            if (!pins.contains(fp)) {
                throw new EkChainException("root cert fingerprint " + fp + " is not in the pinned set");
            }
            anchors.add(new TrustAnchor(root, null));
        }
        if (anchors.isEmpty()) {
            throw new EkChainException("no trusted manufacturer roots configured");
        }
        this.trustAnchors = anchors;
    }

    /** Validate the EK cert (+ optional intermediates) chains to a pinned root. */
    public void validate(X509Certificate ekCert, List<X509Certificate> intermediates) throws EkChainException {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            List<X509Certificate> path = new ArrayList<>();
            path.add(ekCert);
            if (intermediates != null) {
                path.addAll(intermediates);
            }
            PKIXParameters params = new PKIXParameters(trustAnchors);
            params.setRevocationEnabled(false); // CRL/OCSP layered in a later slice (R-3 SLO)
            CertPathValidator.getInstance("PKIX").validate(cf.generateCertPath(path), params);
        } catch (Exception e) {
            throw new EkChainException("EK certificate does not chain to a pinned manufacturer root", e);
        }
    }

    /** Parse a DER-encoded X.509 certificate. */
    public static X509Certificate parseCert(byte[] der) throws CertificateException {
        return (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new ByteArrayInputStream(der));
    }

    private static String sha256Hex(X509Certificate cert) throws EkChainException {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(cert.getEncoded()));
        } catch (Exception e) {
            throw new EkChainException("failed to fingerprint root cert", e);
        }
    }
}

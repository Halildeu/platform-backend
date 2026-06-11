package com.example.endpointadmin.remoteaccess;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * Faz 22.6 B1.4c-3 — parses a PEM-encoded {@code SubjectPublicKeyInfo} ("-----BEGIN PUBLIC KEY-----") into a
 * JDK {@link PublicKey}, the trusted provenance-signing key the real attestation verifiers
 * ({@link KeyBasedAttestationVerifier} / {@link DsseProvenanceVerifier}) check against. JDK-only.
 *
 * <p>The algorithm family is detected by trying the common ones (EC / RSA / EdDSA) — a standard
 * SubjectPublicKeyInfo embeds its algorithm, but the JDK {@link KeyFactory} must be asked by name; the first
 * family that accepts the DER wins. <b>Fail-closed:</b> a blank or unparseable PEM raises
 * {@link GeneralSecurityException} so a mis-configured key fails the bean at startup (never a silent null key).
 */
public final class PublicKeys {

    private static final List<String> KEY_ALGORITHMS = List.of("EC", "RSA", "Ed25519", "EdDSA");

    /** @throws GeneralSecurityException if {@code pem} is blank or not a parseable public key */
    public static PublicKey fromPem(String pem) throws GeneralSecurityException {
        if (pem == null || pem.isBlank()) {
            throw new GeneralSecurityException("public key PEM is blank");
        }
        byte[] der;
        try {
            String base64 = pem.replaceAll("-----BEGIN PUBLIC KEY-----", "")
                    .replaceAll("-----END PUBLIC KEY-----", "")
                    .replaceAll("\\s", "");
            der = Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            throw new GeneralSecurityException("public key PEM is not valid Base64", e);
        }
        X509EncodedKeySpec spec = new X509EncodedKeySpec(der);
        GeneralSecurityException last = null;
        for (String algorithm : KEY_ALGORITHMS) {
            try {
                return KeyFactory.getInstance(algorithm).generatePublic(spec);
            } catch (GeneralSecurityException e) {
                last = e; // try the next family
            }
        }
        throw new GeneralSecurityException("public key PEM is not a supported EC/RSA/EdDSA key", last);
    }

    private PublicKeys() {
    }
}

package com.example.endpointadmin.remoteaccess;

import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.cert.CertificateException;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CRL;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Faz 22.6 T-4a-ii slice-2 (Codex 019ebc7e) — shared, config-driven construction of the B1.4 verifiers, so
 * BOTH the revocation runtime ({@code ScheduledRevocationDriver}) and the remote-bridge broker
 * ({@code PeerTrustLedger} wiring) build them the SAME way from the same {@code endpoint-admin.remote-access.*}
 * config (no second, drifting copy). Behaviour-preserving extraction of the driver's former private
 * builders — every fail-fast rule (invalid enum, unparseable anchor/CRL/key, the factory's forbidden-combo
 * matrix) is unchanged and still runs AT CONSTRUCTION (= startup).
 */
public final class RemoteAccessVerifierFactory {

    private RemoteAccessVerifierFactory() {
    }

    /**
     * Select + safely construct the cert-trust evaluator from config (B1.4a-3). Invalid enum values and an
     * unparseable anchor/CRL bundle FAIL FAST here, as does any forbidden REAL_PKI combination (via
     * {@link CertTrustEvaluatorFactory}). Blank evaluator/mode default to the safe IN_MEMORY/DISABLED.
     */
    public static CertTrustEvaluator buildTrustEvaluator(String evaluatorType, String revocationMode,
                                                         String trustAnchorPem, String trustCrlPem,
                                                         boolean allowInsecureNoRevocation,
                                                         boolean productionLikeProfile, long inMemoryMaxAgeMs) {
        CertTrustEvaluatorFactory.EvaluatorType type = parseEnum(
                evaluatorType, CertTrustEvaluatorFactory.EvaluatorType.class,
                CertTrustEvaluatorFactory.EvaluatorType.IN_MEMORY);
        CertTrustEvaluatorFactory.RevocationMode mode = parseEnum(
                revocationMode, CertTrustEvaluatorFactory.RevocationMode.class,
                CertTrustEvaluatorFactory.RevocationMode.DISABLED);
        Set<TrustAnchor> anchors;
        try {
            anchors = TrustAnchorLoader.fromPemBundle(trustAnchorPem);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "remote-access cert-trust.trust-anchor-pem is not a valid PEM certificate bundle", e);
        }
        List<X509CRL> crls;
        try {
            crls = X509ChainParser.parseCrlBundle(trustCrlPem);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "remote-access cert-trust.crl-pem is not a valid PEM CRL bundle", e);
        }
        return CertTrustEvaluatorFactory.create(
                type, mode, anchors, crls, allowInsecureNoRevocation, productionLikeProfile,
                Duration.ofMillis(inMemoryMaxAgeMs));
    }

    /**
     * Select + safely construct the attestation verifier from config (B1.4c-3). An invalid verifier enum, an
     * unparseable public-key PEM, or any forbidden combination (via {@link AttestationVerifierFactory}) FAIL
     * FAST here. Blank expected builder/policy → {@code null} (the consumer coerces it to deny-all).
     */
    public static AttestationVerifier buildAttestationVerifier(String verifierType, String expectedBuilderId,
                                                              String expectedPolicyHash, String publicKeyPem,
                                                              String signatureAlgorithm,
                                                              boolean productionLikeProfile) {
        AttestationVerifierFactory.VerifierType type = parseEnum(
                verifierType, AttestationVerifierFactory.VerifierType.class,
                AttestationVerifierFactory.VerifierType.IN_MEMORY);
        PublicKey signingKey = null;
        if (publicKeyPem != null && !publicKeyPem.isBlank()) {
            try {
                signingKey = PublicKeys.fromPem(publicKeyPem);
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException(
                        "remote-access attestation.public-key-pem is not a valid public key", e);
            }
        }
        return AttestationVerifierFactory.create(
                type, expectedBuilderId, expectedPolicyHash, signingKey, signatureAlgorithm,
                productionLikeProfile);
    }

    /**
     * Like {@link #buildAttestationVerifier} but NEVER null — an unconfigured policy yields the explicit
     * {@link DenyAllAttestationVerifier} (the remote-bridge {@code PeerTrustLedger} requires a non-null
     * verifier; deny-all is the fail-closed coercion, Codex 019ebc7e).
     */
    public static AttestationVerifier buildAttestationVerifierOrDenyAll(String verifierType,
            String expectedBuilderId, String expectedPolicyHash, String publicKeyPem,
            String signatureAlgorithm, boolean productionLikeProfile) {
        AttestationVerifier verifier = buildAttestationVerifier(verifierType, expectedBuilderId,
                expectedPolicyHash, publicKeyPem, signatureAlgorithm, productionLikeProfile);
        return verifier != null ? verifier : DenyAllAttestationVerifier.INSTANCE;
    }

    /**
     * Construct the device-identity verifier (B1.4d) from config: the device-CA roots an agent's device key
     * must chain to, and the minimum acceptable key-protection level. An unparseable device-CA bundle FAILS
     * FAST. Blank roots → an empty anchor set (the verifier then trusts no device — fail-closed).
     */
    public static DeviceIdentityVerifier buildDeviceIdentityVerifier(String deviceCaPem,
            String protectionLevel) {
        java.util.Set<TrustAnchor> roots;
        try {
            roots = TrustAnchorLoader.fromPemBundle(deviceCaPem);
        } catch (CertificateException e) {
            throw new IllegalStateException(
                    "remote-bridge device-identity device-ca-pem is not a valid PEM certificate bundle", e);
        }
        DeviceIdentityVerifier.DeviceProtectionLevel required = parseEnum(protectionLevel,
                DeviceIdentityVerifier.DeviceProtectionLevel.class,
                DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM);
        return new DeviceIdentityVerifier(roots, required);
    }

    /** Parse a config enum, blank→default, an invalid value → fail-fast (a typo must not silently default). */
    public static <E extends Enum<E>> E parseEnum(String value, Class<E> type, E dflt) {
        if (value == null || value.isBlank()) {
            return dflt;
        }
        try {
            return Enum.valueOf(type, value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                    "invalid remote-access config value '" + value + "' for " + type.getSimpleName());
        }
    }
}

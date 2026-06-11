package com.example.endpointadmin.remoteaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.PublicKey;

/**
 * Faz 22.6 B1.4c-3 — selects + safely constructs the {@link AttestationVerifier} from config, enforcing the
 * blocking matrix at construction (= bean creation = STARTUP fail-fast), mirroring the cert-trust
 * {@link CertTrustEvaluatorFactory}:
 *
 * <ul>
 *   <li><b>unconfigured</b> (blank expected builder/policy) → {@code null}: the heartbeat then coerces a
 *       {@code null} verifier to deny-all (an enabled runtime with no attestation policy refuses every
 *       cert-sampling session — current B1.3b behavior, preserved).</li>
 *   <li>{@link VerifierType#IN_MEMORY} → the B1.3a {@link InMemoryAttestationVerifier} <b>PLACEHOLDER</b>
 *       (a deterministic stand-in, NOT real crypto). Forbidden in a production-like profile → fail-fast; a
 *       real runtime must use a real verifier.</li>
 *   <li>{@link VerifierType#KEY_BASED} → the B1.4c-1 {@link KeyBasedAttestationVerifier} (real signature
 *       over the provenance tuple). Requires a configured public key → fail-fast if absent.</li>
 *   <li>{@link VerifierType#DSSE} → the B1.4c-2 {@link DsseProvenanceVerifier} is built + proven, but it
 *       consumes a raw DSSE envelope, which only the live transport delivers — wiring it through the
 *       heartbeat's evidence needs the C/D transport, so it is rejected here (not yet selectable) rather
 *       than silently building an unreachable verifier.</li>
 * </ul>
 */
public final class AttestationVerifierFactory {

    private static final Logger log = LoggerFactory.getLogger(AttestationVerifierFactory.class);

    public enum VerifierType { IN_MEMORY, KEY_BASED, DSSE }

    private static IllegalStateException reject(String message) {
        log.error("remote-access attestation config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param productionLikeProfile when true, the placeholder IN_MEMORY verifier is REFUSED (a real runtime
     *                              must use a real verifier with a configured key)
     * @return the selected verifier, or {@code null} when unconfigured (heartbeat → deny-all)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static AttestationVerifier create(VerifierType type, String expectedBuilderId,
                                             String expectedPolicyHash, PublicKey signingKey,
                                             String signatureAlgorithm, boolean productionLikeProfile) {
        if (expectedBuilderId == null || expectedBuilderId.isBlank()
                || expectedPolicyHash == null || expectedPolicyHash.isBlank()) {
            return null; // unconfigured → heartbeat deny-all (preserves the B1.3b behavior)
        }
        VerifierType t = type == null ? VerifierType.IN_MEMORY : type;
        switch (t) {
            case IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("attestation verifier IN_MEMORY is a PLACEHOLDER trust basis (not real crypto) "
                            + "and is forbidden in a production-like profile — configure verifier=KEY_BASED with "
                            + "endpoint-admin.remote-access.attestation.public-key-pem");
                }
                return new InMemoryAttestationVerifier(expectedBuilderId, expectedPolicyHash);
            }
            case KEY_BASED -> {
                if (signingKey == null) {
                    throw reject("attestation verifier KEY_BASED requires a configured public key "
                            + "(endpoint-admin.remote-access.attestation.public-key-pem), but none was loaded "
                            + "— a real provenance verifier cannot boot without a trusted signing key");
                }
                return new KeyBasedAttestationVerifier(
                        expectedBuilderId, expectedPolicyHash, signingKey, signatureAlgorithm);
            }
            case DSSE -> throw reject("attestation verifier DSSE is built (B1.4c-2) but not yet selectable: it "
                    + "consumes a raw DSSE envelope that only the live transport delivers — wiring it through "
                    + "the heartbeat evidence is a C/D transport concern, so it is refused here rather than "
                    + "built unreachable");
            default -> throw reject("unreachable attestation verifier type " + t);
        }
    }

    private AttestationVerifierFactory() {
    }
}

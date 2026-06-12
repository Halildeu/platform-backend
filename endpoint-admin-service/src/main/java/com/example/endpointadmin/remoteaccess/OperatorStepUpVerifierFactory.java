package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.security.GeneralSecurityException;
import java.security.PublicKey;

/**
 * Faz 22.6 D operator step-up verifier seam (d-stepup-3, Codex 019ebe06) — selects the operator step-up
 * verifier with a blocking matrix at construction (= bean creation = STARTUP fail-fast), mirroring the B1.4c-3
 * {@code AttestationVerifierFactory} / cert-trust factory:
 * <ul>
 *   <li><b>IN_MEMORY</b> — the d-stepup-1 {@link InMemoryOperatorStepUpVerifier}, a PLACEHOLDER (a
 *       deterministic stand-in, NOT real WebAuthn crypto). FORBIDDEN in a production-like profile → fail-fast;
 *       a real runtime must verify a real assertion.</li>
 *   <li><b>WEBAUTHN</b> — the d-stepup-2 {@link WebAuthnStepUpVerifier} (configured operator public key + JCA
 *       signature over the assertion base). Requires a configured public key → fail-fast if absent/invalid.</li>
 * </ul>
 * The verifier→assembler wiring (which feeds the StepUpState into the broker) stays DEFERRED to the operator-
 * facing transport slice (4c) — this factory only constructs the right verifier fail-closed.
 */
public final class OperatorStepUpVerifierFactory {

    private static final Logger log = LoggerFactory.getLogger(OperatorStepUpVerifierFactory.class);

    public enum VerifierType { IN_MEMORY, WEBAUTHN }

    private OperatorStepUpVerifierFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access operator step-up config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param productionLikeProfile when true, the placeholder IN_MEMORY verifier is REFUSED (a real runtime
     *                              must verify a real WebAuthn assertion)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static OperatorStepUpVerifier create(VerifierType type, MethodStrength inMemoryStrength,
                                                String operatorPublicKeyPem, String signatureAlgorithm,
                                                String expectedOrigin, String expectedRpId,
                                                boolean productionLikeProfile) {
        VerifierType t = type == null ? VerifierType.IN_MEMORY : type; // placeholder default (non-prod only)
        switch (t) {
            case IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("operator step-up verifier IN_MEMORY is a PLACEHOLDER trust basis (not real "
                            + "WebAuthn) and is forbidden in a production-like profile — configure "
                            + "verifier=WEBAUTHN with an operator public key");
                }
                return new InMemoryOperatorStepUpVerifier(inMemoryStrength, expectedOrigin);
            }
            case WEBAUTHN -> {
                if (operatorPublicKeyPem == null || operatorPublicKeyPem.isBlank()) {
                    throw reject("operator step-up verifier WEBAUTHN requires a configured operator public key");
                }
                try {
                    PublicKey key = PublicKeys.fromPem(operatorPublicKeyPem);
                    return new WebAuthnStepUpVerifier(key, signatureAlgorithm, expectedOrigin, expectedRpId);
                } catch (GeneralSecurityException | RuntimeException e) {
                    throw reject("operator step-up verifier WEBAUTHN public key / config invalid: " + e.getMessage());
                }
            }
            default -> throw reject("unreachable operator step-up verifier type " + t);
        }
    }
}

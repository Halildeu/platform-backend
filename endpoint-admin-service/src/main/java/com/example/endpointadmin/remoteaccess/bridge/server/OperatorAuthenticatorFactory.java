package com.example.endpointadmin.remoteaccess.bridge.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 slice-4c-1b (Codex 019ebe06) — selects the operator authenticator with a blocking matrix at
 * construction (= bean creation = STARTUP fail-fast), mirroring the d-stepup-3 / B1.4c-3 factory pattern:
 * <ul>
 *   <li><b>IN_MEMORY</b> — the {@link InMemoryOperatorAuthenticator}, a PLACEHOLDER (constant-time compare to
 *       a configured token, NOT real auth). FORBIDDEN in a production-like profile → fail-fast; a real runtime
 *       must validate a real operator credential.</li>
 *   <li><b>MTLS_CLIENT_CERT / JWT_BEARER</b> — the real operator authenticators (an mTLS client-cert chain
 *       validated to an operator CA, or a JWT validated against the IdP). NOT YET IMPLEMENTED (the live
 *       operator-channel slice) → rejected here rather than silently built, so no half-wired auth path
 *       exists.</li>
 * </ul>
 */
public final class OperatorAuthenticatorFactory {

    private static final Logger log = LoggerFactory.getLogger(OperatorAuthenticatorFactory.class);

    public enum AuthenticatorType { IN_MEMORY, MTLS_CLIENT_CERT, JWT_BEARER }

    private OperatorAuthenticatorFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access operator authenticator config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param productionLikeProfile when true, the placeholder IN_MEMORY authenticator is REFUSED (a real
     *                              runtime must validate a real operator credential)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static OperatorAuthenticator create(AuthenticatorType type, String inMemoryToken,
                                               String inMemorySubject, boolean productionLikeProfile) {
        AuthenticatorType t = type == null ? AuthenticatorType.IN_MEMORY : type; // placeholder default (non-prod)
        switch (t) {
            case IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("operator authenticator IN_MEMORY is a PLACEHOLDER trust basis (not real auth) "
                            + "and is forbidden in a production-like profile — configure a real operator "
                            + "authenticator (mTLS client cert or JWT)");
                }
                return new InMemoryOperatorAuthenticator(inMemoryToken, inMemorySubject);
            }
            case MTLS_CLIENT_CERT -> throw reject("operator authenticator MTLS_CLIENT_CERT is not yet "
                    + "implemented (the live operator-channel slice) — refusing rather than half-wiring auth");
            case JWT_BEARER -> throw reject("operator authenticator JWT_BEARER is not yet implemented (the live "
                    + "operator-channel slice) — refusing rather than half-wiring auth");
            default -> throw reject("unreachable operator authenticator type " + t);
        }
    }
}

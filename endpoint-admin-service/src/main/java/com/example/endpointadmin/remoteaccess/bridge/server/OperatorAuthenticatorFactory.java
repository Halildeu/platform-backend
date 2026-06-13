package com.example.endpointadmin.remoteaccess.bridge.server;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Faz 22.6 slice-4c-1b (Codex 019ebe06) — selects the operator authenticator with a blocking matrix at
 * construction (= bean creation = STARTUP fail-fast), mirroring the d-stepup-3 / B1.4c-3 factory pattern:
 * <ul>
 *   <li><b>IN_MEMORY</b> — the {@link InMemoryOperatorAuthenticator}, a PLACEHOLDER (constant-time compare to
 *       a configured token, NOT real auth). FORBIDDEN in a production-like profile → fail-fast; a real runtime
 *       must validate a real operator credential.</li>
 *   <li><b>JWT_BEARER</b> — the real operator authenticator ({@link JwtBearerOperatorAuthenticator}, the
 *       human-to-console channel: an OIDC/JWT validated against the IdP/Keycloak JWKS). Prod-legal, but only
 *       with a full config ({@link JwtBearerConfig}); a missing decoder/issuer/audience/claim/role config is a
 *       fail-fast reject (D10 Workstream-0, 3-line consensus JWT/Keycloak).</li>
 *   <li><b>MTLS_CLIENT_CERT</b> — the mTLS client-cert operator authenticator. NOT YET IMPLEMENTED (kept for a
 *       future regulated-prod / break-glass operator channel; the machine channel already uses mTLS) → rejected
 *       rather than silently built, so no half-wired auth path exists.</li>
 * </ul>
 */
public final class OperatorAuthenticatorFactory {

    private static final Logger log = LoggerFactory.getLogger(OperatorAuthenticatorFactory.class);

    public enum AuthenticatorType { IN_MEMORY, MTLS_CLIENT_CERT, JWT_BEARER }

    /**
     * Config for the {@link JwtBearerOperatorAuthenticator} (only consulted for {@code JWT_BEARER}). The decoder
     * is a bridge-specific {@code JwtDecoder} (signature + issuer against the IdP JWKS, but WITHOUT the main
     * app's audience validator, so the bridge audience is enforced by the authenticator). Any null/blank field
     * is a fail-fast config error.
     */
    public record JwtBearerConfig(JwtDecoder jwtDecoder, String issuer, String audience, String tenantClaim,
                                  String subjectClaim, String roleClaimPath, String requiredOperatorRole) {
    }

    private OperatorAuthenticatorFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access operator authenticator config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * @param jwtConfig             the JWT_BEARER config (only consulted for {@code JWT_BEARER}; null/incomplete
     *                              there is a fail-fast reject)
     * @param productionLikeProfile when true, the placeholder IN_MEMORY authenticator is REFUSED (a real
     *                              runtime must validate a real operator credential)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static OperatorAuthenticator create(AuthenticatorType type, String inMemoryToken,
                                               String inMemorySubject, String inMemoryTenant,
                                               JwtBearerConfig jwtConfig,
                                               boolean productionLikeProfile) {
        AuthenticatorType t = type == null ? AuthenticatorType.IN_MEMORY : type; // placeholder default (non-prod)
        switch (t) {
            case IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("operator authenticator IN_MEMORY is a PLACEHOLDER trust basis (not real auth) "
                            + "and is forbidden in a production-like profile — configure a real operator "
                            + "authenticator (JWT bearer)");
                }
                return new InMemoryOperatorAuthenticator(inMemoryToken, inMemorySubject, inMemoryTenant);
            }
            case JWT_BEARER -> {
                if (jwtConfig == null || jwtConfig.jwtDecoder() == null || blank(jwtConfig.issuer())
                        || blank(jwtConfig.audience()) || blank(jwtConfig.tenantClaim())
                        || blank(jwtConfig.subjectClaim()) || blank(jwtConfig.roleClaimPath())
                        || blank(jwtConfig.requiredOperatorRole())) {
                    throw reject("operator authenticator JWT_BEARER requires a JwtDecoder + issuer + audience + "
                            + "tenant/subject/role-claim + required-operator-role config (config-shaped fail-fast)");
                }
                return new JwtBearerOperatorAuthenticator(jwtConfig.jwtDecoder(), jwtConfig.issuer(),
                        jwtConfig.audience(), jwtConfig.tenantClaim(), jwtConfig.subjectClaim(),
                        jwtConfig.roleClaimPath(), jwtConfig.requiredOperatorRole());
            }
            case MTLS_CLIENT_CERT -> throw reject("operator authenticator MTLS_CLIENT_CERT is not yet "
                    + "implemented (kept for a future regulated-prod / break-glass operator channel) — refusing "
                    + "rather than half-wiring auth");
            default -> throw reject("unreachable operator authenticator type " + t);
        }
    }
}

package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Faz 22.6 D10 Workstream-0 (Codex 019ebe06; 3-line consensus JWT/Keycloak) — the real operator authenticator
 * for the human-to-console channel: validate an OIDC/JWT bearer token minted by the platform IdP (Keycloak).
 *
 * <p><b>Why JWT (not mTLS) for the operator:</b> the operator is a HUMAN on a web console, so they live in the
 * IdP/OIDC/JWT world (identity, role, tenant, MFA, audit subject from Keycloak), while the AGENT is a machine and
 * stays on mTLS/device-PKI. Two distinct trust domains — the industry-standard split (BeyondTrust, CyberArk,
 * Teleport, MS Remote Help). Per-action step-up stays on the existing WebAuthn handler; this is the baseline auth.
 *
 * <p><b>Fail-closed contract</b> ({@link OperatorAuthenticator}): a missing/blank/invalid credential yields
 * {@link OperatorIdentity#unauthenticated()} — never throws, never null. The injected {@link JwtDecoder} does
 * signature + temporal (+ issuer) validation against the JWKS (a {@code NimbusJwtDecoder}); any decode failure
 * (bad signature, expired, malformed, JWKS/network fault) is caught and fails closed. This authenticator then
 * enforces the operator-policy claims EXPLICITLY:
 * <ol>
 *   <li><b>iss</b> — the token came from the configured IdP;</li>
 *   <li><b>aud</b> — the token was minted for THIS bridge (so a token for another service cannot be replayed);</li>
 *   <li><b>tenant</b> — a canonical-UUID tenancy boundary (slice-4c-2b-0: an authenticated operator MUST carry a
 *       tenant, derived from the VERIFIED token — never a client-supplied field);</li>
 *   <li><b>subject</b> — a wire-valid operator subject id;</li>
 *   <li><b>role</b> — the token carries the required remote-bridge operator role (authorization, not just authn).</li>
 * </ol>
 */
public final class JwtBearerOperatorAuthenticator implements OperatorAuthenticator {

    private final JwtDecoder jwtDecoder;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final String tenantClaim;
    private final String subjectClaim;
    private final String roleClaimPath;     // "realm_access.roles" (Keycloak nested) or a flat claim name
    private final String requiredOperatorRole;

    public JwtBearerOperatorAuthenticator(JwtDecoder jwtDecoder, String expectedIssuer, String expectedAudience,
                                          String tenantClaim, String subjectClaim, String roleClaimPath,
                                          String requiredOperatorRole) {
        this.jwtDecoder = Objects.requireNonNull(jwtDecoder, "jwtDecoder");
        this.expectedIssuer = requireNonBlank(expectedIssuer, "expectedIssuer");
        this.expectedAudience = requireNonBlank(expectedAudience, "expectedAudience");
        this.tenantClaim = requireNonBlank(tenantClaim, "tenantClaim");
        this.subjectClaim = requireNonBlank(subjectClaim, "subjectClaim");
        this.roleClaimPath = requireNonBlank(roleClaimPath, "roleClaimPath");
        this.requiredOperatorRole = requireNonBlank(requiredOperatorRole, "requiredOperatorRole");
    }

    @Override
    public OperatorIdentity authenticate(OperatorCredential credential) {
        if (credential == null) {
            return OperatorIdentity.unauthenticated();
        }
        String token = credential.bearerToken().orElse(null);
        if (token == null || token.isBlank()) {
            return OperatorIdentity.unauthenticated();
        }

        Jwt jwt;
        try {
            jwt = jwtDecoder.decode(token); // signature + exp/nbf (+ issuer) via JWKS; throws on any invalidity
        } catch (RuntimeException invalid) {
            return OperatorIdentity.unauthenticated(); // fail-closed: bad sig / expired / malformed / JWKS fault
        }
        if (jwt == null) {
            return OperatorIdentity.unauthenticated();
        }

        // iss — the token MUST originate from the configured IdP (belt-and-suspenders with the decoder's validator,
        // and the authoritative check when a test injects a stub decoder)
        if (!expectedIssuer.equals(jwt.getClaimAsString("iss"))) {
            return OperatorIdentity.unauthenticated();
        }
        // aud — the token MUST be minted for THIS bridge; a main-app/other-service token must NOT be replayable
        List<String> audience = jwt.getAudience();
        if (audience == null || !audience.contains(expectedAudience)) {
            return OperatorIdentity.unauthenticated();
        }
        // tenant — an authenticated operator MUST carry a canonical-UUID tenancy boundary (from the verified token)
        String tenant = jwt.getClaimAsString(tenantClaim);
        if (!isCanonicalUuid(tenant)) {
            return OperatorIdentity.unauthenticated();
        }
        // subject — a wire-valid operator subject id (consistent with WireContract everywhere else)
        String subject = jwt.getClaimAsString(subjectClaim);
        if (subject == null || !WireContract.isValidId(subject)) {
            return OperatorIdentity.unauthenticated();
        }
        // role — the token MUST carry the required operator role (authorization gate, not just authentication)
        if (!extractRoles(jwt).contains(requiredOperatorRole)) {
            return OperatorIdentity.unauthenticated();
        }

        return OperatorIdentity.of(tenant, subject, AuthMethod.JWT_BEARER);
    }

    /**
     * Read the roles from {@code roleClaimPath}: a dotted path ({@code realm_access.roles}, the Keycloak default
     * mirrored from this app's existing converter) reads the nested list; a flat name reads a top-level list. A
     * missing/wrong-shaped claim yields no roles (fail-closed — the role check then denies).
     */
    private List<String> extractRoles(Jwt jwt) {
        int dot = roleClaimPath.indexOf('.');
        if (dot < 0) {
            List<String> flat = jwt.getClaimAsStringList(roleClaimPath);
            return flat == null ? List.of() : flat;
        }
        Object namespace = jwt.getClaim(roleClaimPath.substring(0, dot));
        if (!(namespace instanceof Map<?, ?> map)) {
            return List.of();
        }
        Object roles = map.get(roleClaimPath.substring(dot + 1));
        if (!(roles instanceof List<?> list)) {
            return List.of();
        }
        return list.stream().filter(String.class::isInstance).map(String.class::cast).toList();
    }

    private static boolean isCanonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            // strict canonical round-trip (mirrors the #614 operatorTenantId store guard): rejects uppercase /
            // no-dash / padded / non-UUID, so the tenancy boundary is unambiguous
            return UUID.fromString(value).toString().equals(value);
        } catch (IllegalArgumentException notAUuid) {
            return false;
        }
    }

    private static String requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
        return value;
    }
}

package com.serban.notify.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #734 (Codex 019ef41c): the notify JWT authorities converter must map the
 * auth-service SERVICE-token `perm` claim (raw, no prefix) so the internal
 * system-submit path's {@code hasAuthority("SVC_notify:intents:system")} gate is
 * satisfied — while still mapping the user-token `permissions` claim and
 * Keycloak `realm_access.roles`.
 */
class SecurityConfigConverterTest {

    private final JwtAuthenticationConverter converter =
            new SecurityConfig(new MockEnvironment()).notifyJwtAuthenticationConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(300));
        claims.forEach(b::claim);
        return b.build();
    }

    private Set<String> authorities(Jwt jwt) {
        AbstractAuthenticationToken auth = converter.convert(jwt);
        return auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());
    }

    @Test
    void serviceTokenPermClaim_fromServiceIssuer_mapsToSvcAuthority() {
        // auth-service service token (iss=auth-service): perm → SVC_ authority.
        Set<String> a = authorities(jwt(Map.of(
                "iss", "auth-service",
                "svc", "user-service",
                "perm", List.of("notify:intents:system"))));
        assertTrue(a.contains("SVC_notify:intents:system"),
                "service perm claim from the service issuer must map to the SVC_ authority the internal path gate requires");
    }

    @Test
    void permClaim_fromNonServiceIssuer_doesNotGetSvcAuthority() {
        // A token whose iss is NOT the service issuer (e.g. a Keycloak user token)
        // must NEVER gain the internal system authority, even carrying perm/permissions.
        Set<String> a = authorities(jwt(Map.of(
                "iss", "https://keycloak.example/realms/platform",
                "sub", "kc-uuid",
                "perm", List.of("notify:intents:system"),
                "permissions", List.of("notify:intents:system"))));
        assertFalse(a.contains("SVC_notify:intents:system"),
                "non-service-issuer token must not gain SVC_ system authority");
    }

    @Test
    void userTokenPermissionsClaim_stillMaps() {
        Set<String> a = authorities(jwt(Map.of(
                "sub", "kc-uuid",
                "permissions", List.of("USER_MANAGEMENT"))));
        assertTrue(a.contains("USER_MANAGEMENT"));
    }

    @Test
    void realmAccessRoles_stillMapToRolePrefix() {
        Set<String> a = authorities(jwt(Map.of(
                "sub", "kc-uuid",
                "realm_access", Map.of("roles", List.of("PRIVACY_OFFICER")))));
        assertTrue(a.contains("ROLE_PRIVACY_OFFICER"));
    }

    @Test
    void userTokenWithoutPerm_hasNoSystemAuthority() {
        // A normal user token must NOT carry the system-submit authority.
        Set<String> a = authorities(jwt(Map.of(
                "sub", "kc-uuid",
                "permissions", List.of("USER_MANAGEMENT"))));
        assertFalse(a.contains("notify:intents:system"));
    }
}

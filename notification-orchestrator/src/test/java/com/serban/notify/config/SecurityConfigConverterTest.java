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
 * system-submit path's {@code hasAuthority("notify:intents:system")} gate is
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
    void serviceTokenPermClaim_mapsToRawAuthority() {
        // auth-service service token: perm=["notify:intents:system"], svc principal.
        Set<String> a = authorities(jwt(Map.of(
                "svc", "user-service",
                "perm", List.of("notify:intents:system"))));
        assertTrue(a.contains("notify:intents:system"),
                "service perm claim must map to the raw authority used by the internal path gate");
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

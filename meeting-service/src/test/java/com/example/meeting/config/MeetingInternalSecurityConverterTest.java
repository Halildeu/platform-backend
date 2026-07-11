package com.example.meeting.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/**
 * ai#244 BE-1b — converter iss-guard unit test.
 *
 * <p>The {@link SecurityConfig#jwtAuthenticationConverter()} maps an
 * auth-service SERVICE-token {@code perm} claim to a {@code SVC_}-prefixed
 * authority (the authority the internal chain's
 * {@code hasAuthority("SVC_meeting:analysis-result:write")} gate requires) ONLY
 * when the token's {@code iss} equals the configured service issuer
 * ({@code auth-service}). A Keycloak USER token — always a different issuer —
 * must never gain a {@code SVC_} authority even if it carries a {@code perm}
 * claim, while its {@code realm_access.roles} → {@code ROLE_*} and {@code scope}
 * → {@code SCOPE_*} mappings are preserved.
 *
 * <p>This is the primary No-Fake-Work negative-check target:
 * {@link #permFromNonServiceIssuer_doesNotGainSvcAuthority()} turns RED the
 * moment the {@code iss == serviceIssuer} guard is loosened.
 */
class MeetingInternalSecurityConverterTest {

    private static final String SVC_WRITE = "SVC_meeting:analysis-result:write";

    // Fresh MockEnvironment ⇒ no MEETING_INTERNAL_SERVICE_JWT_ISSUER override,
    // so the converter resolves the default service issuer "auth-service"
    // (matches auth-service ServiceTokenProvider's iss claim).
    private final JwtAuthenticationConverter converter =
            new SecurityConfig(new MockEnvironment()).jwtAuthenticationConverter();

    private static Jwt jwt(Map<String, Object> claims) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256")
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
    void serviceTokenPermFromServiceIssuer_mapsToSvcAuthority() {
        Set<String> a = authorities(jwt(Map.of(
                "iss", "auth-service",
                "sub", "meeting-ai-service",
                "svc", "meeting-ai-service",
                "perm", List.of("meeting:analysis-result:write"))));
        assertTrue(a.contains(SVC_WRITE),
                "service perm from the service issuer must map to the SVC_ authority the internal path gate requires");
    }

    @Test
    void permFromNonServiceIssuer_doesNotGainSvcAuthority() {
        // A Keycloak USER token (iss = realm URL) that somehow carries perm must
        // NEVER gain the internal SVC_ authority. Loosening the converter's
        // iss-guard makes this assertion fail — the No-Fake-Work canary.
        Set<String> a = authorities(jwt(Map.of(
                "iss", "http://localhost:8081/realms/serban",
                "sub", "kc-user-uuid",
                "perm", List.of("meeting:analysis-result:write"))));
        assertFalse(a.contains(SVC_WRITE),
                "non-service-issuer token must not gain the SVC_ internal authority");
    }

    @Test
    void serviceIssuerButNoPerm_hasNoSvcAuthority() {
        Set<String> a = authorities(jwt(Map.of(
                "iss", "auth-service",
                "sub", "meeting-ai-service",
                "svc", "meeting-ai-service")));
        assertFalse(a.contains(SVC_WRITE),
                "a service-issuer token without a perm claim must not carry any SVC_ authority");
    }

    @Test
    void keycloakRealmRoles_stillMapToUppercaseRolePrefix() {
        // Existing behaviour preserved: realm_access.roles → ROLE_<UPPER>.
        Set<String> a = authorities(jwt(Map.of(
                "iss", "http://localhost:8081/realms/serban",
                "sub", "kc-user-uuid",
                "realm_access", Map.of("roles", List.of("admin", "meeting_admin")))));
        assertTrue(a.contains("ROLE_ADMIN"));
        assertTrue(a.contains("ROLE_MEETING_ADMIN"));
    }

    @Test
    void keycloakScope_stillMapsToScopePrefix() {
        // Existing behaviour preserved: scope → SCOPE_*.
        Set<String> a = authorities(jwt(Map.of(
                "iss", "http://localhost:8081/realms/serban",
                "sub", "kc-user-uuid",
                "scope", "openid email meeting")));
        assertTrue(a.contains("SCOPE_openid"));
        assertTrue(a.contains("SCOPE_meeting"));
    }
}

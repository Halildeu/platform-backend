package com.example.endpointadmin.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtTenantContextResolverTest {

    private static final UUID UUID_TENANT = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesNumericTenantIdWithCompanyScopeFallback() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("tenantId", 1)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        AdminTenantContext context = new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId()).isEqualTo(companyTenant("1"));
        assertThat(context.subject()).isEqualTo("subject-1");
    }

    @Test
    void acceptsMatchingCanonicalAndCompatibilityClaims() {
        UUID companyTenant = companyTenant("42");
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("org_id", companyTenant.toString())
                .claim("tenantId", "42")
                .claim("companyId", "42")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        AdminTenantContext context = new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId()).isEqualTo(companyTenant);
    }

    @Test
    void rejectsConflictingCanonicalAndCompatibilityClaims() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("org_id", UUID_TENANT.toString())
                .claim("tenantId", "1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    @Test
    void rejectsInvalidPresentTenantClaimEvenWhenCompanyIdIsValid() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("tenantId", "1abc")
                .claim("companyId", "42")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    @Test
    void rejectsConflictingLegacyAliases() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("tenantId", "1")
                .claim("companyId", "42")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    @Test
    void compatibilityModeKeepsCanonicalOrgWhileMigrationIsExplicitlyOpen() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("org_id", UUID_TENANT.toString())
                .claim("tenantId", "1")
                .claim("companyId", "1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        AdminTenantContext context = new JwtTenantContextResolver("", false, new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId()).isEqualTo(UUID_TENANT);
    }

    @Test
    void compatibilityModeStillRejectsLegacyConflictsWithoutCanonicalOrg() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("tenantId", "1")
                .claim("companyId", "42")
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", false, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    @Test
    void compatibilityModeStillRejectsConflictingCanonicalAliases() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("org_id", UUID_TENANT.toString())
                .claim("orgId", UUID.randomUUID().toString())
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", false, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    private static UUID companyTenant(String companyId) {
        return UUID.nameUUIDFromBytes(("company:" + companyId).getBytes(StandardCharsets.UTF_8));
    }
}

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

        AdminTenantContext context = new JwtTenantContextResolver("", true, "", new MockEnvironment()).resolveRequired();

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

        AdminTenantContext context = new JwtTenantContextResolver("", true, "", new MockEnvironment()).resolveRequired();

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

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, "", new MockEnvironment()).resolveRequired())
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

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, "", new MockEnvironment()).resolveRequired())
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

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, "", new MockEnvironment()).resolveRequired())
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

        AdminTenantContext context = new JwtTenantContextResolver("", false, "", new MockEnvironment()).resolveRequired();

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

        assertThatThrownBy(() -> new JwtTenantContextResolver("", false, "", new MockEnvironment()).resolveRequired())
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

        assertThatThrownBy(() -> new JwtTenantContextResolver("", false, "", new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    private static UUID companyTenant(String companyId) {
        return UUID.nameUUIDFromBytes(("company:" + companyId).getBytes(StandardCharsets.UTF_8));
    }

    private static final String ORG_A = "68c73eb9-c410-37dc-aff7-5ade8fbbcbb7";
    private static final String TENANT_FIXTURE = "00000000-0000-0000-0000-000000000001";
    private static final String ORG_B = "11111111-2222-3333-4444-555555555555";
    private static final String TENANT_OTHER = "00000000-0000-0000-0000-000000000002";

    private static void authenticateWithOrg(String orgId) {
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .subject("admin@example.com")
                .claim("org_id", orgId)
                .build();
        // Authorities are what make the token authenticated; without them the resolver never
        // reaches the claim path and every alias assertion would pass for the wrong reason.
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));
    }

    /**
     * board #2559 — measured on testai 2026-07-17: an admin whose token carried org
     * 68c73eb9-… saw an empty fleet because all 11 enrolled devices are stamped with the fixed
     * tenant 00000000-…-0001. Tenant isolation was working; the device-management product simply
     * had no devices in it. An explicit alias lets a deployment state the mapping until enrollment
     * derives the tenant from an operator-issued credential.
     */
    @Test
    void explicitAliasRedirectsExactlyTheDeclaredOrg() {
        authenticateWithOrg(ORG_A);

        AdminTenantContext context = new JwtTenantContextResolver(
                "", true, ORG_A + "=" + TENANT_FIXTURE, new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId()).isEqualTo(UUID.fromString(TENANT_FIXTURE));
    }

    @Test
    void aliasIsALookupNotAFallback_unlistedOrgKeepsItsOwnTenant() {
        authenticateWithOrg(ORG_B);

        AdminTenantContext context = new JwtTenantContextResolver(
                "", true, ORG_A + "=" + TENANT_FIXTURE, new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId())
                .as("declaring an alias for one org must not redirect anyone else")
                .isEqualTo(UUID.fromString(ORG_B));
    }

    @Test
    void noAliasesConfigured_behavesExactlyAsBefore() {
        authenticateWithOrg(ORG_A);

        AdminTenantContext context = new JwtTenantContextResolver(
                "", true, "", new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId())
                .as("production configures none, so production is untouched")
                .isEqualTo(UUID.fromString(ORG_A));
    }

    @Test
    void malformedAliasIsRejectedAtStartup_notSilentlySkipped() {
        // A typo'd alias would leave an admin staring at an empty fleet — the exact failure this
        // feature exists to end — so it must fail loudly at wiring time.
        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, "not-a-uuid=" + TENANT_FIXTURE, new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, ORG_A, new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, ORG_A + "=" + ORG_A + "," + ORG_A + "=" + TENANT_FIXTURE, new MockEnvironment()))
                .as("one org mapped to two tenants is ambiguous, not a merge")
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void multipleAliasesAreIndependent() {
        var resolver = new JwtTenantContextResolver(
                "", true, ORG_A + "=" + TENANT_FIXTURE + " , " + ORG_B + "=" + TENANT_OTHER, new MockEnvironment());

        authenticateWithOrg(ORG_A);
        assertThat(resolver.resolveRequired().tenantId()).isEqualTo(UUID.fromString(TENANT_FIXTURE));
        authenticateWithOrg(ORG_B);
        assertThat(resolver.resolveRequired().tenantId()).isEqualTo(UUID.fromString(TENANT_OTHER));
    }

    /** Two orgs sharing one tenant would hand both of them the same devices — reject at startup. */
    @Test
    void aliasTargetMustBeUniqueAcrossOrgs() {
        assertThatThrownBy(() -> new JwtTenantContextResolver(
                "", true, ORG_A + "=" + TENANT_FIXTURE + "," + ORG_B + "=" + TENANT_FIXTURE, new MockEnvironment()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one-to-one");
    }

    /**
     * A token carrying only {@code tenant_id} never proved which org it speaks for, so the alias
     * must not fire: otherwise the token could redirect itself into the alias target.
     */
    @Test
    void legacyTenantClaimIsNotAliased() {
        var resolver = new JwtTenantContextResolver(
                "", true, ORG_A + "=" + TENANT_FIXTURE, new MockEnvironment());

        // No canonical org claim: the alias key itself arrives as a bare tenant_id. Aliasing this
        // would let the token redirect itself into the alias target without ever proving the org.
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none")
                .subject("admin@example.com")
                .claim("tenant_id", ORG_A)
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt, List.of(new SimpleGrantedAuthority("SCOPE_endpoint-admin"))));

        assertThat(resolver.resolveRequired().tenantId())
                .as("legacy path must keep its pre-alias behaviour")
                .isEqualTo(UUID.fromString(ORG_A));
    }
}

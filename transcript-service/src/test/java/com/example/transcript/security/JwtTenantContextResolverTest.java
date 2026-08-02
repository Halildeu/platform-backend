package com.example.transcript.security;

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
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

        AdminTenantContext context = new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId()).isEqualTo(companyTenant("1"));
        // Faz 24 #824: subject is the stable OIDC sub; authzPrincipal is the
        // legacy userId claim used by the module-gate for backward compatibility.
        assertThat(context.subject()).isEqualTo("subject-1");
        assertThat(context.authzPrincipal()).isEqualTo("user-1");
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
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

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
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

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
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

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
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

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
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

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
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

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
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", false, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    // ── Faz 24 issue #824 — subject vs authzPrincipal split ────────────

    @Test
    void authzPrincipalFallsBackToStableSubWhenNoUserIdClaim() {
        // No userId claim on the JWT → both `subject` and `authzPrincipal` come
        // from the stable OIDC `sub`. Existing OpenFGA/module authz rows keyed
        // on `sub` keep working because the fallback preserves the same value.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("stable-sub-42")
                .claim("org_id", UUID_TENANT.toString())
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

        AdminTenantContext context = new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired();

        assertThat(context.subject()).isEqualTo("stable-sub-42");
        assertThat(context.authzPrincipal()).isEqualTo("stable-sub-42");
    }

    @Test
    void failsClosedWhenStableSubIsAbsent() {
        // A JWT without `sub` cannot produce a durable audit identity. Falling
        // back to email/preferred_username is deliberately NOT permitted —
        // those can change during a user's lifetime and break audit lineage.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("") // no stable sub
                .claim("org_id", UUID_TENANT.toString())
                .claim("email", "someone@example.com")
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("401 UNAUTHORIZED");
    }

    @Test
    void userIdClaimChangeDoesNotShiftAuditSubject() {
        // The same human whose numeric userId claim was rotated (real
        // 2026-07-12 incident against #822) MUST still produce the same
        // audit `subject` because `sub` stayed stable. Only `authzPrincipal`
        // rotates with the module-gate compatibility claim.
        Jwt tokenBefore = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("stable-sub-42")
                .claim("org_id", UUID_TENANT.toString())
                .claim("userId", "1")
                .build();
        Jwt tokenAfter = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("stable-sub-42")
                .claim("org_id", UUID_TENANT.toString())
                .claim("userId", "4")
                .build();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                tokenBefore,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));
        AdminTenantContext before = new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                tokenAfter,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));
        AdminTenantContext after = new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired();

        assertThat(before.subject()).isEqualTo(after.subject()).isEqualTo("stable-sub-42");
        assertThat(before.authzPrincipal()).isEqualTo("1");
        assertThat(after.authzPrincipal()).isEqualTo("4");
    }

    private static UUID companyTenant(String companyId) {
        return UUID.nameUUIDFromBytes(("company:" + companyId).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void aCanonicalOrgWinsOverALegacyCompanyIdFromADifferentNamespace() {
        // The live failure, reproduced. A real token carries the canonical org UUID and,
        // alongside it, the legacy numeric companyId the account was created under. The
        // resolver hashed "company:35" into a synthetic UUID and compared it with the
        // canonical one; they are different identifier spaces and never agree, so every
        // request answered 401 "Conflicting tenant claims" — indefinitely, for a token in
        // which nothing was wrong.
        //
        // The existing tests could not catch this: each one sets org_id to the company
        // hash itself, so the two values always matched and the comparison never had an
        // opinion.
        UUID canonicalOrg = UUID.fromString("00000000-0000-0000-0000-000000000001");
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("org_id", canonicalOrg.toString())
                .claim("companyId", "35")
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

        AdminTenantContext context = new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired();

        assertThat(context.tenantId()).isEqualTo(canonicalOrg);
        assertThat(context.tenantId()).isNotEqualTo(companyTenant("35"));
    }

    @Test
    void twoCanonicalClaimsThatDisagreeAreStillRejected() {
        // The namespace argument does not extend to org_id vs tenant_id: those two ARE the
        // same identifier space, so a disagreement between them is a genuine signal and
        // the check stays.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("subject-1")
                .claim("org_id", "00000000-0000-0000-0000-000000000001")
                .claim("tenant_id", "00000000-0000-0000-0000-000000000002")
                .claim("userId", "user-1")
                .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(
                jwt,
                List.of(new SimpleGrantedAuthority("SCOPE_transcript"))));

        assertThatThrownBy(() -> new JwtTenantContextResolver("", true, new MockEnvironment()).resolveRequired())
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                .hasMessageContaining("Conflicting tenant claims");
    }

}

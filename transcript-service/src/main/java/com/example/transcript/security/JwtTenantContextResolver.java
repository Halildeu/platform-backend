package com.example.transcript.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

/**
 * Resolves the {@link AdminTenantContext} (tenant scope + subject + authz
 * principal) from the authenticated JWT principal.
 *
 * <p>Faz 24 issue #824 splits {@code subject} and {@code authzPrincipal}:
 *
 * <ul>
 *   <li>{@code subject} — the STABLE OIDC {@code sub}. Written to audit columns
 *       ({@code accessor_subject}, {@code created_by_subject}) and used for
 *       cross-service audit correlation. If the JWT lacks {@code sub}, the
 *       request FAILS CLOSED (401) — email/username is not accepted as a
 *       durable identity, because those can change during a user's lifetime.
 *   <li>{@code authzPrincipal} — the {@code userId} claim if present, else
 *       {@code sub}. Used by the existing {@code @RequireModule} OpenFGA gate
 *       ({@code TranscriptRequireModuleInterceptor#extractUserId}). Kept for
 *       live-tuple compatibility until module authz is migrated to stable-sub.
 * </ul>
 */
@Component
public class JwtTenantContextResolver implements TenantContextResolver {

    private static final List<String> CANONICAL_ORG_CLAIMS = List.of(
            "org_id",
            "orgId",
            "organization_id",
            "organizationId"
    );
    private static final List<String> COMPAT_TENANT_CLAIMS = List.of(
            "tenant_id",
            "tenantId",
            "company_uuid",
            "companyUuid"
    );
    private static final List<String> COMPANY_ID_CLAIMS = List.of("companyId", "company_id");
    private static final String COMPANY_TENANT_PREFIX = "company:";

    private final UUID localDefaultTenantId;
    private final boolean enforceClaimConsistency;
    private final Environment environment;

    public JwtTenantContextResolver(
            @Value("${transcript.tenant.local-default-tenant-id:}") String localDefaultTenantId,
            @Value("${transcript.tenant.enforce-claim-consistency:true}") boolean enforceClaimConsistency,
            Environment environment
    ) {
        this.localDefaultTenantId = parseUuid(localDefaultTenantId);
        this.enforceClaimConsistency = enforceClaimConsistency;
        this.environment = environment;
    }

    @Override
    public AdminTenantContext resolveRequired() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return localContextOrReject();
        }
        if (authentication.getPrincipal() instanceof Jwt jwt) {
            UUID tenantId = resolveTenantId(jwt);
            String subject = jwt.getSubject();
            String authzPrincipal = resolveAuthzPrincipal(jwt);
            if (tenantId == null || subject == null || subject.isBlank()) {
                // Faz 24 #824: no stable OIDC sub → fail closed. email /
                // preferred_username are never accepted as durable identity.
                return localContextOrReject();
            }
            String effectiveAuthzPrincipal =
                    (authzPrincipal == null || authzPrincipal.isBlank()) ? subject : authzPrincipal;
            return new AdminTenantContext(tenantId, subject, effectiveAuthzPrincipal);
        }
        return localContextOrReject();
    }

    /**
     * Module-gate authz principal (unchanged from previous behavior):
     * {@code userId} claim if present, else {@code sub}. MUST match
     * {@code TranscriptRequireModuleInterceptor#extractUserId}.
     *
     * <p>This value goes into {@link AdminTenantContext#authzPrincipal()} —
     * the audit boundary now uses {@link Jwt#getSubject()} directly and is
     * NEVER derived from userId.
     */
    private String resolveAuthzPrincipal(Jwt jwt) {
        Object userIdClaim = jwt.getClaim("userId");
        if (userIdClaim != null) {
            String value = String.valueOf(userIdClaim);
            if (!value.isBlank()) {
                return value;
            }
        }
        return jwt.getSubject();
    }

    private UUID resolveTenantId(Jwt jwt) {
        UUID canonicalOrg = resolveUuidAliases(jwt, CANONICAL_ORG_CLAIMS, true);
        boolean canonicalTransition = canonicalOrg != null && !enforceClaimConsistency;
        UUID compatibilityTenant = resolveUuidAliases(jwt, COMPAT_TENANT_CLAIMS, !canonicalTransition);
        UUID companyTenant = resolveCompanyAliases(jwt, !canonicalTransition);

        if (canonicalOrg != null) {
            mergeTenantClaims(canonicalOrg, compatibilityTenant, enforceClaimConsistency);
            mergeTenantClaims(canonicalOrg, companyTenant, enforceClaimConsistency);
            return canonicalOrg;
        }
        return mergeTenantClaims(compatibilityTenant, companyTenant, true);
    }

    private UUID resolveUuidAliases(Jwt jwt, List<String> claimNames, boolean rejectConflicts) {
        UUID resolved = null;
        for (String claimName : claimNames) {
            Object rawClaim = jwt.getClaim(claimName);
            if (rawClaim == null) {
                continue;
            }
            String claimValue = claimToString(rawClaim);
            UUID candidate = parseUuid(claimValue);
            if (candidate == null) {
                candidate = parseNumericTenantId(claimValue);
            }
            if (candidate == null) {
                throw new ResponseStatusException(UNAUTHORIZED, "Tenant claim is invalid.");
            }
            resolved = mergeTenantClaims(resolved, candidate, rejectConflicts);
        }
        return resolved;
    }

    private UUID resolveCompanyAliases(Jwt jwt, boolean rejectConflicts) {
        UUID resolved = null;
        for (String claimName : COMPANY_ID_CLAIMS) {
            Object rawClaim = jwt.getClaim(claimName);
            if (rawClaim == null) {
                continue;
            }
            String companyId = claimToString(rawClaim);
            if (companyId == null || companyId.isBlank()) {
                throw new ResponseStatusException(UNAUTHORIZED, "Tenant claim is invalid.");
            }
            resolved = mergeTenantClaims(resolved, tenantIdFromCompanyScope(companyId), rejectConflicts);
        }
        return resolved;
    }

    private static UUID mergeTenantClaims(UUID current, UUID candidate, boolean rejectConflicts) {
        if (candidate == null) {
            return current;
        }
        if (current != null && !current.equals(candidate) && rejectConflicts) {
            throw new ResponseStatusException(UNAUTHORIZED, "Conflicting tenant claims.");
        }
        return current != null ? current : candidate;
    }

    private AdminTenantContext localContextOrReject() {
        if (isLocalLikeProfile() && localDefaultTenantId != null) {
            return new AdminTenantContext(localDefaultTenantId, "local-dev", "local-dev");
        }
        throw new ResponseStatusException(UNAUTHORIZED, "Tenant context could not be resolved from authenticated principal.");
    }

    private boolean isLocalLikeProfile() {
        for (String profile : environment.getActiveProfiles()) {
            if ("local".equalsIgnoreCase(profile) || "dev".equalsIgnoreCase(profile) || "test".equalsIgnoreCase(profile)) {
                return true;
            }
        }
        return false;
    }

    private static String claimToString(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Number number ? String.valueOf(number.longValue()) : String.valueOf(value);
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(value.trim());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static UUID parseNumericTenantId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        if (!trimmed.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return null;
        }
        return tenantIdFromCompanyScope(trimmed);
    }

    private static UUID tenantIdFromCompanyScope(String companyId) {
        return UUID.nameUUIDFromBytes((COMPANY_TENANT_PREFIX + companyId.trim()).getBytes(StandardCharsets.UTF_8));
    }
}

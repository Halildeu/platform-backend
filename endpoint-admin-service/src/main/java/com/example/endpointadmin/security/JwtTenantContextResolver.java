package com.example.endpointadmin.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

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
    /** Explicit {@code orgId -> tenantId} aliases; empty unless a deployment configures them. */
    private final Map<UUID, UUID> orgTenantAliases;

    public JwtTenantContextResolver(
            @Value("${endpoint-admin.tenant.local-default-tenant-id:}") String localDefaultTenantId,
            @Value("${endpoint-admin.tenant.enforce-claim-consistency:true}") boolean enforceClaimConsistency,
            @Value("${endpoint-admin.tenant.org-aliases:}") String orgAliases,
            Environment environment
    ) {
        this.localDefaultTenantId = parseUuid(localDefaultTenantId);
        this.enforceClaimConsistency = enforceClaimConsistency;
        this.environment = environment;
        this.orgTenantAliases = parseOrgAliases(orgAliases);
    }

    /**
     * Parses {@code endpoint-admin.tenant.org-aliases} — a comma-separated list of
     * {@code <orgUuid>=<tenantUuid>} pairs (board #2559).
     *
     * <p><b>Why this exists.</b> Devices enrolled through the mTLS/TPM paths are stamped with a
     * fixed tenant from configuration, not with the org of whoever registered them. An admin whose
     * token carries a real org therefore sees an empty fleet: the product is behaving correctly
     * (tenant isolation works), but the device-management product has no devices in it. Until
     * enrollment derives the tenant from an operator-issued, tenant-scoped credential — the real
     * fix — a deployment can declare exactly which org maps to which tenant.
     *
     * <p><b>Deliberately narrow.</b> Each alias is an exact {@code org -> tenant} pair: no
     * wildcard, no "pick the first org", no global fallback. An org that is not listed keeps its own
     * org id as tenant, so adding an alias can never widen anyone else's view. The property is empty
     * by default, which means production is unaffected unless someone writes the pair down.
     *
     * <p>Malformed entries are rejected at startup rather than skipped: a typo'd alias would
     * silently leave an admin looking at an empty fleet, which is exactly the failure this is meant
     * to end.
     */
    private static Map<UUID, UUID> parseOrgAliases(String raw) {
        if (raw == null || raw.isBlank()) {
            return Map.of();
        }
        Map<UUID, UUID> aliases = new LinkedHashMap<>();
        for (String entry : raw.split(",")) {
            String pair = entry.trim();
            if (pair.isEmpty()) {
                continue;
            }
            int eq = pair.indexOf('=');
            if (eq <= 0 || eq == pair.length() - 1) {
                throw new IllegalArgumentException(
                        "endpoint-admin.tenant.org-aliases entry must be <orgUuid>=<tenantUuid>, got: " + pair);
            }
            UUID org = parseUuidOrThrow(pair.substring(0, eq).trim(), pair);
            UUID tenant = parseUuidOrThrow(pair.substring(eq + 1).trim(), pair);
            UUID previous = aliases.put(org, tenant);
            if (previous != null && !previous.equals(tenant)) {
                throw new IllegalArgumentException(
                        "endpoint-admin.tenant.org-aliases maps org " + org + " to two tenants");
            }
        }
        return Map.copyOf(aliases);
    }

    private static UUID parseUuidOrThrow(String value, String entry) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(
                    "endpoint-admin.tenant.org-aliases has a non-UUID value in entry: " + entry, ex);
        }
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
            String subject = firstNonBlank(jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("preferred_username"));
            if (tenantId != null && subject != null && !subject.isBlank()) {
                return new AdminTenantContext(tenantId, subject);
            }
        }
        return localContextOrReject();
    }

    private UUID resolveTenantId(Jwt jwt) {
        UUID canonicalOrg = resolveUuidAliases(jwt, CANONICAL_ORG_CLAIMS, true);
        boolean canonicalTransition = canonicalOrg != null && !enforceClaimConsistency;
        UUID compatibilityTenant = resolveUuidAliases(jwt, COMPAT_TENANT_CLAIMS, !canonicalTransition);
        UUID companyTenant = resolveCompanyAliases(jwt, !canonicalTransition);

        if (canonicalOrg != null) {
            mergeTenantClaims(canonicalOrg, compatibilityTenant, enforceClaimConsistency);
            mergeTenantClaims(canonicalOrg, companyTenant, enforceClaimConsistency);
            return aliasFor(canonicalOrg);
        }
        return aliasFor(mergeTenantClaims(compatibilityTenant, companyTenant, true));
    }

    /**
     * Applies a configured {@code org -> tenant} alias, if one was declared for exactly this org.
     *
     * <p>Applied last, after the claims have been resolved and cross-checked, so an alias can only
     * redirect an org that the token genuinely proved. An org with no alias is returned untouched —
     * the mapping is a lookup, never a fallback — so a deployment that declares none behaves exactly
     * as before. See {@link #parseOrgAliases} for why this exists at all.
     */
    private UUID aliasFor(UUID resolved) {
        if (resolved == null) {
            return null;
        }
        UUID alias = orgTenantAliases.get(resolved);
        return alias == null ? resolved : alias;
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
            return new AdminTenantContext(localDefaultTenantId, "local-dev");
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

    private static String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

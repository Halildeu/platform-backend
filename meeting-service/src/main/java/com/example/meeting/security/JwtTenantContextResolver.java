package com.example.meeting.security;

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
 * Resolves the {@link AdminTenantContext} from the authenticated JWT.
 * Mirrors the endpoint-admin resolver: tries a list of UUID tenant
 * claims, then a hashed company-id fallback, and finally a local-dev
 * default (only under local/dev/test profiles). Copied from
 * endpoint-admin-service to keep the cross-service tenant-resolution
 * contract identical.
 */
@Component
public class JwtTenantContextResolver implements TenantContextResolver {

    private static final List<String> TENANT_UUID_CLAIMS = List.of(
            "tenant_id",
            "tenantId",
            "organization_id",
            "organizationId",
            "org_id",
            "orgId",
            "company_uuid",
            "companyUuid"
    );
    private static final List<String> COMPANY_ID_CLAIMS = List.of("companyId", "company_id");
    private static final String COMPANY_TENANT_PREFIX = "company:";

    private final UUID localDefaultTenantId;
    private final Environment environment;

    public JwtTenantContextResolver(
            @Value("${meeting.tenant.local-default-tenant-id:}") String localDefaultTenantId,
            Environment environment
    ) {
        this.localDefaultTenantId = parseUuid(localDefaultTenantId);
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
            String subject = firstNonBlank(jwt.getSubject(),
                    jwt.getClaimAsString("email"),
                    jwt.getClaimAsString("preferred_username"));
            if (tenantId != null && subject != null && !subject.isBlank()) {
                return new AdminTenantContext(tenantId, subject, resolveAuthzPrincipal(jwt));
            }
        }
        return localContextOrReject();
    }

    /**
     * The OpenFGA authz principal — MUST mirror
     * {@code MeetingRequireModuleInterceptor#extractUserId}: the {@code userId}
     * claim when present, otherwise {@code sub}. Keeps the owner-tuple write
     * and the module gate on the same principal. Falls back to {@code subject}
     * only if {@code sub} is somehow blank (caller already guaranteed a
     * non-blank subject above).
     */
    private static String resolveAuthzPrincipal(Jwt jwt) {
        Object userIdClaim = jwt.getClaim("userId");
        if (userIdClaim != null) {
            String userId = String.valueOf(userIdClaim);
            if (!userId.isBlank()) {
                return userId;
            }
        }
        return jwt.getSubject();
    }

    private UUID resolveTenantId(Jwt jwt) {
        for (String claimName : TENANT_UUID_CLAIMS) {
            String claimValue = claimToString(jwt.getClaim(claimName));
            UUID parsed = parseUuid(claimValue);
            if (parsed != null) {
                return parsed;
            }
            UUID numericTenant = parseNumericTenantId(claimValue);
            if (numericTenant != null) {
                return numericTenant;
            }
        }
        for (String claimName : COMPANY_ID_CLAIMS) {
            String companyId = claimToString(jwt.getClaim(claimName));
            if (companyId != null && !companyId.isBlank()) {
                return tenantIdFromCompanyScope(companyId);
            }
        }
        return null;
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

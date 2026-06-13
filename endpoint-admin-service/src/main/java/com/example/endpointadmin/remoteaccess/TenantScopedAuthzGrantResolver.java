package com.example.endpointadmin.remoteaccess;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Faz 22.6 D10 approval-chain (Codex 019ebe06) — an in-memory {@link AuthzGrantResolver} whose grants are
 * TENANT-SCOPED: a principal granted {@code can_request} / {@code can_approve} in a tenant holds it for ANY
 * session in that tenant.
 *
 * <p><b>Why (Codex):</b> the approval resource id is {@code remote_session:<tenant>:<sessionId>} with a DYNAMIC
 * sessionId, so an exact-(principal,resourceId) grant set (the {@link InMemoryAuthzGrantResolver}) would have to
 * enumerate every future sessionId and would fail-closed-deny every live session. The pilot grant config is
 * therefore expressed per (principal → tenants), and this resolver matches by parsing the resource's tenant.
 * The grant is still on the RAW principal (decision A preserved); alias/canonical mapping for grants stays the
 * live OpenFGA resolver's job.
 *
 * <p><b>Fail-closed:</b> a blank principal/resource, a resource that is not a well-formed
 * {@code remote_session:<tenant>:<sessionId>}, or a principal/tenant with no grant → false. A PLACEHOLDER for
 * the non-prod pilot ({@link AuthzGrantResolverFactory} forbids it in production).
 */
public final class TenantScopedAuthzGrantResolver implements AuthzGrantResolver {

    private static final String RESOURCE_PREFIX = "remote_session";

    private final Map<String, Set<String>> canRequestTenantsByPrincipal;
    private final Map<String, Set<String>> canApproveTenantsByPrincipal;

    /**
     * @param canRequestTenantsByPrincipal principal → the tenants in which they may request ANY session
     * @param canApproveTenantsByPrincipal principal → the tenants in which they may approve ANY session
     */
    public TenantScopedAuthzGrantResolver(Map<String, Set<String>> canRequestTenantsByPrincipal,
                                          Map<String, Set<String>> canApproveTenantsByPrincipal) {
        this.canRequestTenantsByPrincipal = normalize(canRequestTenantsByPrincipal, "canRequest");
        this.canApproveTenantsByPrincipal = normalize(canApproveTenantsByPrincipal, "canApprove");
    }

    private static Map<String, Set<String>> normalize(Map<String, Set<String>> in, String label) {
        if (in == null) {
            throw new IllegalArgumentException(label + " grant map must not be null");
        }
        Map<String, Set<String>> out = new HashMap<>();
        in.forEach((principal, tenants) -> {
            if (principal == null || principal.isBlank()) {
                throw new IllegalArgumentException(label + " grant has a blank principal");
            }
            if (tenants == null || tenants.isEmpty()) {
                throw new IllegalArgumentException(label + " grant for '" + principal + "' names no tenant");
            }
            Set<String> normalizedTenants = new HashSet<>();
            for (String tenant : tenants) {
                if (tenant == null || tenant.isBlank()) {
                    throw new IllegalArgumentException(label + " grant for '" + principal + "' has a blank tenant");
                }
                normalizedTenants.add(tenant.strip());
            }
            if (out.put(principal.strip(), Set.copyOf(normalizedTenants)) != null) {
                throw new IllegalArgumentException(label + " grant has a duplicate normalized principal: "
                        + principal.strip());
            }
        });
        return Map.copyOf(out);
    }

    @Override
    public boolean hasCanRequest(String principalId, String sessionResourceId) {
        return grantsTenant(canRequestTenantsByPrincipal, principalId, sessionResourceId);
    }

    @Override
    public boolean hasCanApprove(String principalId, String sessionResourceId) {
        return grantsTenant(canApproveTenantsByPrincipal, principalId, sessionResourceId);
    }

    private static boolean grantsTenant(Map<String, Set<String>> grants, String principalId,
                                        String sessionResourceId) {
        if (principalId == null || principalId.isBlank()) {
            return false;
        }
        String tenant = tenantOf(sessionResourceId);
        if (tenant == null) {
            return false; // not a well-formed remote_session resource → fail-closed
        }
        Set<String> tenants = grants.get(principalId.strip());
        return tenants != null && tenants.contains(tenant);
    }

    /**
     * Extract {@code <tenant>} from {@code remote_session:<tenant>:<sessionId>}. The tenant is a canonical UUID
     * (no colon); the sessionId may contain colons (the wire-id charset includes {@code :}), so a 3-limit split
     * keeps the whole sessionId in the last segment. Any other shape → null (fail-closed).
     */
    private static String tenantOf(String sessionResourceId) {
        if (sessionResourceId == null || sessionResourceId.isBlank()) {
            return null;
        }
        String[] parts = sessionResourceId.split(":", 3);
        if (parts.length != 3 || !RESOURCE_PREFIX.equals(parts[0])
                || parts[1].isBlank() || parts[2].isBlank()) {
            return null;
        }
        return parts[1];
    }
}

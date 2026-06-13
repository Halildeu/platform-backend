package com.example.endpointadmin.remoteaccess;

import java.util.HashSet;
import java.util.Set;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — the in-memory REFERENCE {@link AuthzGrantResolver}: a configured static
 * grant-set for tests + the disabled-by-default skeleton. A PLACEHOLDER trust basis, NOT the live OpenFGA authz
 * plane; the {@link AuthzGrantResolverFactory} forbids it in a production-like profile.
 *
 * <p><b>Fail-closed:</b> a (principal, resource) pair NOT in the configured set has no grant; a null/blank
 * argument has no grant. The grant lookup is on the RAW presented principal (alias/proxy/service-account →
 * canonical mapping is the live resolver's job — a raw miss is a safe deny). Keys are trimmed; a blank principal
 * or resource at construction is rejected (a blank grant would be a fail-open hole), and a normalized-duplicate
 * grant (e.g. {@code "u1"} and {@code " u1 "} for the same resource) is rejected as a likely config typo.
 */
public final class InMemoryAuthzGrantResolver implements AuthzGrantResolver {

    /** A single configured grant: {@code principalId} holds a relation on {@code sessionResourceId}. */
    public record Grant(String principalId, String sessionResourceId) {
    }

    private final Set<Grant> canRequestGrants;
    private final Set<Grant> canApproveGrants;

    /**
     * @param canRequestGrants the principals granted {@code can_request} per resource (trimmed; blank rejected)
     * @param canApproveGrants the principals granted {@code can_approve} per resource (trimmed; blank rejected)
     */
    public InMemoryAuthzGrantResolver(Set<Grant> canRequestGrants, Set<Grant> canApproveGrants) {
        this.canRequestGrants = normalize(canRequestGrants, "canRequest");
        this.canApproveGrants = normalize(canApproveGrants, "canApprove");
    }

    private static Set<Grant> normalize(Set<Grant> grants, String label) {
        if (grants == null) {
            throw new IllegalArgumentException(label + " grant set must not be null");
        }
        Set<Grant> normalized = new HashSet<>();
        for (Grant g : grants) {
            if (g == null || g.principalId() == null || g.principalId().isBlank()
                    || g.sessionResourceId() == null || g.sessionResourceId().isBlank()) {
                throw new IllegalArgumentException(label + " grant has a blank principal or resource");
            }
            normalized.add(new Grant(g.principalId().strip(), g.sessionResourceId().strip()));
        }
        // a whitespace-variant pair (e.g. "u1" and " u1 " on the same resource) collapses on normalization — a
        // shrink means the config carried an ambiguous duplicate; reject so the grant-set is unambiguous (Codex)
        if (normalized.size() != grants.size()) {
            throw new IllegalArgumentException(label + " has normalized-duplicate grants (a likely config typo)");
        }
        return Set.copyOf(normalized);
    }

    @Override
    public boolean hasCanRequest(String principalId, String sessionResourceId) {
        return contains(canRequestGrants, principalId, sessionResourceId);
    }

    @Override
    public boolean hasCanApprove(String principalId, String sessionResourceId) {
        return contains(canApproveGrants, principalId, sessionResourceId);
    }

    private static boolean contains(Set<Grant> grants, String principalId, String sessionResourceId) {
        if (principalId == null || principalId.isBlank()
                || sessionResourceId == null || sessionResourceId.isBlank()) {
            return false; // fail-closed: an unidentifiable principal/resource holds no grant
        }
        return grants.contains(new Grant(principalId.strip(), sessionResourceId.strip()));
    }
}

package com.example.endpointadmin.remoteaccess;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;

/**
 * Faz 22.6 D10-E10 (Codex 019ebe06) — selects the {@link AuthzGrantResolver} with a blocking matrix at
 * construction (= bean creation = STARTUP fail-fast), mirroring the {@link CanonicalIdentityResolverFactory}
 * pattern:
 * <ul>
 *   <li><b>IN_MEMORY</b> — the {@link InMemoryAuthzGrantResolver}, a PLACEHOLDER static grant-set (NOT the live
 *       OpenFGA authz plane). FORBIDDEN in a production-like profile → fail-fast; a real runtime must check the
 *       {@code can_request} / {@code can_approve} grants against the live authz model.</li>
 *   <li><b>OPENFGA_BACKED</b> — the real resolver (an OpenFGA check on the {@code remote_session} relations).
 *       NOT YET IMPLEMENTED (the live authz-plane slice) → rejected here rather than silently built, so no
 *       half-wired grant check exists (which would let a missing/forged grant defeat dual-control).</li>
 * </ul>
 */
public final class AuthzGrantResolverFactory {

    private static final Logger log = LoggerFactory.getLogger(AuthzGrantResolverFactory.class);

    public enum GrantSource { IN_MEMORY, TENANT_SCOPED_IN_MEMORY, OPENFGA_BACKED }

    private AuthzGrantResolverFactory() {
    }

    private static IllegalStateException reject(String message) {
        log.error("remote-access authz-grant resolver config REJECTED (fail-fast): {}", message);
        return new IllegalStateException(message);
    }

    /**
     * @param type                  the grant source (null defaults to the placeholder IN_MEMORY, non-prod only)
     * @param canRequestGrants      the reference {@code can_request} grants (used only for IN_MEMORY)
     * @param canApproveGrants      the reference {@code can_approve} grants (used only for IN_MEMORY)
     * @param productionLikeProfile when true, the placeholder IN_MEMORY resolver is REFUSED (a real runtime must
     *                              check grants against the live OpenFGA model)
     * @throws IllegalStateException on any forbidden combination (fail-fast startup)
     */
    public static AuthzGrantResolver create(GrantSource type,
                                            Set<InMemoryAuthzGrantResolver.Grant> canRequestGrants,
                                            Set<InMemoryAuthzGrantResolver.Grant> canApproveGrants,
                                            boolean productionLikeProfile) {
        GrantSource t = type == null ? GrantSource.IN_MEMORY : type; // placeholder default (non-prod)
        switch (t) {
            case IN_MEMORY -> {
                if (productionLikeProfile) {
                    throw reject("authz-grant resolver IN_MEMORY is a PLACEHOLDER static grant-set (not the live "
                            + "OpenFGA authz plane) and is forbidden in a production-like profile — configure an "
                            + "OpenFGA-backed resolver so can_request/can_approve grants are authoritative");
                }
                return new InMemoryAuthzGrantResolver(
                        canRequestGrants == null ? Set.of() : canRequestGrants,
                        canApproveGrants == null ? Set.of() : canApproveGrants);
            }
            case TENANT_SCOPED_IN_MEMORY -> throw reject("authz-grant resolver TENANT_SCOPED_IN_MEMORY must be "
                    + "built via createTenantScoped(...) (it takes tenant-scoped grant maps, not exact grant sets)");
            case OPENFGA_BACKED -> throw reject("authz-grant resolver OPENFGA_BACKED is not yet implemented (the "
                    + "live authz-plane slice) — refusing rather than half-wiring the dual-control grant check");
            default -> throw reject("unreachable authz-grant resolver type " + t);
        }
    }

    /**
     * Build the {@link TenantScopedAuthzGrantResolver} (the pilot grant source: a principal granted in a tenant
     * holds it for ANY session in that tenant — needed because the approval resource's sessionId is dynamic). A
     * PLACEHOLDER in-memory grant config → FORBIDDEN in a production-like profile (the live OpenFGA model is
     * authoritative in prod).
     *
     * @param productionLikeProfile when true, the placeholder tenant-scoped grant config is REFUSED
     * @throws IllegalStateException when forbidden (fail-fast startup)
     */
    public static AuthzGrantResolver createTenantScoped(Map<String, Set<String>> canRequestTenantsByPrincipal,
                                                        Map<String, Set<String>> canApproveTenantsByPrincipal,
                                                        boolean productionLikeProfile) {
        if (productionLikeProfile) {
            throw reject("authz-grant resolver TENANT_SCOPED_IN_MEMORY is a PLACEHOLDER tenant-scoped grant "
                    + "config (not the live OpenFGA authz plane) and is forbidden in a production-like profile");
        }
        return new TenantScopedAuthzGrantResolver(
                canRequestTenantsByPrincipal == null ? Map.of() : canRequestTenantsByPrincipal,
                canApproveTenantsByPrincipal == null ? Map.of() : canApproveTenantsByPrincipal);
    }
}

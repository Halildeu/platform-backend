package com.example.commonauth.scope;

import java.util.Collections;
import java.util.Set;

/**
 * Immutable holder for the current user's authorized scope IDs.
 * Populated per-request by {@link ScopeContextFilter}.
 *
 * Production: populated from OpenFGA listObjects.
 * Dev/permitAll: populated from YAML config (erp.dev.scope.*).
 *
 * Downstream consumers (Hibernate @Filter, RLS, RowFilterInjector)
 * read from {@link ScopeContextHolder#get()} — they never check
 * which mode the application is running in.
 */
public record ScopeContext(
        String userId,
        Set<Long> allowedCompanyIds,
        Set<Long> allowedProjectIds,
        Set<Long> allowedWarehouseIds,
        boolean superAdmin
) {
    public ScopeContext {
        allowedCompanyIds = allowedCompanyIds == null ? Collections.emptySet() : Set.copyOf(allowedCompanyIds);
        allowedProjectIds = allowedProjectIds == null ? Collections.emptySet() : Set.copyOf(allowedProjectIds);
        allowedWarehouseIds = allowedWarehouseIds == null ? Collections.emptySet() : Set.copyOf(allowedWarehouseIds);
    }

    /**
     * Convenience: get the current request's ScopeContext.
     */
    public static ScopeContext current() {
        return ScopeContextHolder.get();
    }

    /**
     * Empty context — blocks all scoped data access.
     */
    public static ScopeContext empty(String userId) {
        return new ScopeContext(userId, Set.of(), Set.of(), Set.of(), false);
    }

    /**
     * SuperAdmin context — bypasses all scope filters.
     */
    public static ScopeContext superAdmin(String userId) {
        return new ScopeContext(userId, Set.of(), Set.of(), Set.of(), true);
    }

    public boolean canAccessCompany(Long companyId) {
        return superAdmin || (companyId != null && allowedCompanyIds.contains(companyId));
    }

    public boolean canAccessProject(Long projectId) {
        return superAdmin || (projectId != null && allowedProjectIds.contains(projectId));
    }

    public boolean canAccessWarehouse(Long warehouseId) {
        return superAdmin || (warehouseId != null && allowedWarehouseIds.contains(warehouseId));
    }
}

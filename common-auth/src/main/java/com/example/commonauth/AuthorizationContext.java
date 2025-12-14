package com.example.commonauth;

import java.util.Collections;
import java.util.Set;

public final class AuthorizationContext {
    private final Long userId;
    private final String email;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final Set<Long> allowedCompanyIds;
    private final Set<Long> allowedProjectIds;
    private final Set<Long> allowedWarehouseIds;

    private AuthorizationContext(Long userId,
                                String email,
                                Set<String> roles,
                                Set<String> permissions,
                                Set<Long> allowedCompanyIds,
                                Set<Long> allowedProjectIds,
                                Set<Long> allowedWarehouseIds) {
        this.userId = userId;
        this.email = email;
        this.roles = roles == null ? Collections.emptySet() : Set.copyOf(roles);
        this.permissions = permissions == null ? Collections.emptySet() : Set.copyOf(permissions);
        this.allowedCompanyIds = allowedCompanyIds == null ? Collections.emptySet() : Set.copyOf(allowedCompanyIds);
        this.allowedProjectIds = allowedProjectIds == null ? Collections.emptySet() : Set.copyOf(allowedProjectIds);
        this.allowedWarehouseIds = allowedWarehouseIds == null ? Collections.emptySet() : Set.copyOf(allowedWarehouseIds);
    }

    public static AuthorizationContext of(Long userId,
                                          String email,
                                          Set<String> roles,
                                          Set<String> permissions) {
        return new AuthorizationContext(userId, email, roles, permissions, Set.of(), Set.of(), Set.of());
    }

    public static AuthorizationContext of(Long userId,
                                          String email,
                                          Set<String> roles,
                                          Set<String> permissions,
                                          Set<Long> allowedCompanyIds,
                                          Set<Long> allowedProjectIds,
                                          Set<Long> allowedWarehouseIds) {
        return new AuthorizationContext(userId, email, roles, permissions, allowedCompanyIds, allowedProjectIds, allowedWarehouseIds);
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public Set<String> getRoles() {
        return roles;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public Set<Long> getAllowedCompanyIds() {
        return allowedCompanyIds;
    }

    public Set<Long> getAllowedProjectIds() {
        return allowedProjectIds;
    }

    public Set<Long> getAllowedWarehouseIds() {
        return allowedWarehouseIds;
    }

    public boolean isAdmin() {
        return roles.stream().anyMatch(r -> r != null && r.equalsIgnoreCase("ADMIN"))
                || permissions.stream().anyMatch(p -> p != null && p.equalsIgnoreCase("ADMIN"));
    }

    public boolean hasPermission(String code) {
        if (code == null || code.isBlank()) {
            return false;
        }
        String target = code.trim().toUpperCase();
        return permissions.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> p.trim().toUpperCase())
                .anyMatch(target::equals);
    }

    public boolean canAccessCompany(Long companyId) {
        return companyId != null && allowedCompanyIds.contains(companyId);
    }

    public boolean canAccessProject(Long projectId) {
        return projectId != null && allowedProjectIds.contains(projectId);
    }

    public boolean canAccessWarehouse(Long warehouseId) {
        return warehouseId != null && allowedWarehouseIds.contains(warehouseId);
    }
}

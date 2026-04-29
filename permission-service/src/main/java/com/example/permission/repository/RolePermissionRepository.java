package com.example.permission.repository;

import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {

    List<RolePermission> findByRole(Role role);

    List<RolePermission> findByRoleId(Long roleId);

    List<RolePermission> findByRoleIdAndPermissionType(Long roleId, PermissionType permissionType);

    // 2026-04-18 OI-03 canary: JOIN FETCH rp.role eager-loads the Role entity
    // to prevent LazyInitializationException when callers (e.g.
    // AuthorizationControllerV1#/assignments which is not @Transactional and
    // runs with spring.jpa.open-in-view=false) pass the result to
    // TupleSyncService#resolveEffectiveGrants, which dereferences
    // rp.getRole().getName(). Fetch-join is single-query (no N+1), harmless
    // for the @Transactional(readOnly=true) callers that already had a
    // session open.
    @Query("SELECT rp FROM RolePermission rp JOIN FETCH rp.role WHERE rp.role.id IN :roleIds")
    List<RolePermission> findByRoleIdIn(List<Long> roleIds);

    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.role.id = :roleId")
    void deleteByRoleId(Long roleId);

    /**
     * Codex 019dd818 iter-17 (Plan C drift guard): row-shape evidence query
     * for legacy write rejection.
     *
     * <p>Used by {@code AccessControllerV1.rejectIfGranuleManaged} to mirror
     * the OR predicate in {@link
     * com.example.permission.config.PermissionDataInitializer#usesGranuleModel}.
     * If a role's {@code permission_model} marker drifted to LEGACY but the
     * table still carries granule-shape rows, the legacy endpoints must
     * still reject writes — otherwise we'd recreate the mixed FK + granule
     * state V15/V16/V17 cleaned up.
     *
     * <p>Query is intentionally bounded ({@code count > 0}, no row fetch)
     * and lookup-only — does not open the role aggregate's lazy collection,
     * so it works with {@code spring.jpa.open-in-view=false}.
     */
    @Query("""
            SELECT (COUNT(rp) > 0)
            FROM RolePermission rp
            WHERE rp.role.id = :roleId
              AND rp.permission IS NULL
              AND rp.permissionType IS NOT NULL
              AND rp.permissionKey IS NOT NULL
              AND rp.grantType IS NOT NULL
            """)
    boolean existsGranuleShapeByRoleId(Long roleId);
}

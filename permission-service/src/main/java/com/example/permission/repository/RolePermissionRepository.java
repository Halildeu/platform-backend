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
}

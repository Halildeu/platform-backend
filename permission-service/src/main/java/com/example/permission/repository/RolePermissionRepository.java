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

    @Query("SELECT rp FROM RolePermission rp WHERE rp.role.id IN :roleIds")
    List<RolePermission> findByRoleIdIn(List<Long> roleIds);

    @Modifying
    @Query("DELETE FROM RolePermission rp WHERE rp.role.id = :roleId")
    void deleteByRoleId(Long roleId);
}

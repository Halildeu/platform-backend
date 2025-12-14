package com.example.permission.repository;

import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RolePermissionRepository extends JpaRepository<RolePermission, Long> {
    List<RolePermission> findByRole(Role role);
}

package com.example.permission.repository;

import com.example.permission.model.Permission;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PermissionRepository extends JpaRepository<Permission, Long> {
    Optional<Permission> findByCodeIgnoreCase(String code);
}

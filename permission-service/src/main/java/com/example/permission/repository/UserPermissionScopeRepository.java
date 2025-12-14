package com.example.permission.repository;

import com.example.permission.model.UserPermissionScope;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserPermissionScopeRepository extends JpaRepository<UserPermissionScope, Long> {

    @Query("""
            select ups from UserPermissionScope ups
            join fetch ups.permission p
            join fetch ups.scope s
            where ups.userId = :userId
            """)
    List<UserPermissionScope> findByUserIdWithPermissionAndScope(@Param("userId") Long userId);

    boolean existsByUserIdAndPermission_CodeIgnoreCaseAndScope_Id(Long userId, String permissionCode, Long scopeId);

    void deleteByUserIdAndPermission_CodeIgnoreCaseAndScope_Id(Long userId, String permissionCode, Long scopeId);
}

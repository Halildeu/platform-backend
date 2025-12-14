package com.example.permission.repository;

import com.example.permission.model.UserRoleAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRoleAssignmentRepository extends JpaRepository<UserRoleAssignment, Long> {

    @Query("""
            select distinct assignment from UserRoleAssignment assignment
            join fetch assignment.role role
            left join fetch role.rolePermissions rolePermissions
            left join fetch rolePermissions.permission permission
            where assignment.userId = :userId
            and assignment.active = true
            and (
                (:companyId is null and assignment.companyId is null)
                or (:companyId is not null and assignment.companyId = :companyId)
            )
            and (:projectId is null or assignment.projectId is null or assignment.projectId = :projectId)
            and (:warehouseId is null or assignment.warehouseId is null or assignment.warehouseId = :warehouseId)
            """)
    List<UserRoleAssignment> findActiveAssignments(@Param("userId") Long userId,
                                                   @Param("companyId") Long companyId,
                                                   @Param("projectId") Long projectId,
                                                   @Param("warehouseId") Long warehouseId);

    @Query("""
            select assignment from UserRoleAssignment assignment
            where assignment.userId = :userId
            and assignment.role.id = :roleId
            and assignment.active = true
            and (
                (:companyId is null and assignment.companyId is null)
                or (:companyId is not null and assignment.companyId = :companyId)
            )
            and ((:projectId is null and assignment.projectId is null) or assignment.projectId = :projectId)
            and ((:warehouseId is null and assignment.warehouseId is null) or assignment.warehouseId = :warehouseId)
            """)
    Optional<UserRoleAssignment> findActiveAssignment(@Param("userId") Long userId,
                                                      @Param("companyId") Long companyId,
                                                      @Param("roleId") Long roleId,
                                                      @Param("projectId") Long projectId,
                                                      @Param("warehouseId") Long warehouseId);

    @Query("""
            select distinct assignment from UserRoleAssignment assignment
            join fetch assignment.role role
            left join fetch role.rolePermissions rolePermissions
            left join fetch rolePermissions.permission permission
            where assignment.userId = :userId
            and assignment.active = true
            """)
    List<UserRoleAssignment> findActiveAssignments(@Param("userId") Long userId);

    List<UserRoleAssignment> findByUserIdAndCompanyIdAndActiveTrue(Long userId, Long companyId);

    long countByRoleAndActiveTrue(com.example.permission.model.Role role);
}

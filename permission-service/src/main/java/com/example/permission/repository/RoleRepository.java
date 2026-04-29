package com.example.permission.repository;

import com.example.permission.model.Role;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RoleRepository extends JpaRepository<Role, Long> {
    Optional<Role> findByNameIgnoreCase(String name);

    /**
     * Codex 019dd9f0 iter-22 (A++ hotfix): pessimistic write lock for the
     * granule-replace endpoint. Two parallel PUT /v1/roles/{id}/granules
     * requests against the same role would otherwise race on the
     * "clear → flush → insert" sequence and one of them would crash on the
     * partial unique index {@code uk_role_permissions_role_granule}.
     * Serializing at the parent role acquires {@code FOR UPDATE} on the
     * row, so the second writer waits until the first commits.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Role r WHERE r.id = :roleId")
    Optional<Role> findByIdForUpdate(@Param("roleId") Long roleId);
}

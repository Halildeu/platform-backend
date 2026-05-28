package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.PolicyApprovalStatus;
import com.example.endpointadmin.model.PolicyChangeApproval;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PolicyChangeApprovalRepository extends JpaRepository<PolicyChangeApproval, UUID> {

    Optional<PolicyChangeApproval> findByTenantIdAndId(UUID tenantId, UUID id);

    /**
     * Wave-12 PR-5 — filtered list for the admin console. {@code status},
     * {@code target} (policyId), {@code proposerSubject} are independently
     * optional; passing {@code null} for any means "do not filter on this
     * field". Results ordered by newest-first ({@code createdAt DESC}).
     */
    @Query("""
            SELECT a
              FROM PolicyChangeApproval a
             WHERE a.tenantId = :tenantId
               AND (:status IS NULL OR a.status = :status)
               AND (:target IS NULL OR a.target = :target)
               AND (:proposerSubject IS NULL
                    OR a.proposerSubject = :proposerSubject)
             ORDER BY a.createdAt DESC, a.id DESC
            """)
    List<PolicyChangeApproval> search(@Param("tenantId") UUID tenantId,
                                      @Param("status") PolicyApprovalStatus status,
                                      @Param("target") String target,
                                      @Param("proposerSubject") String proposerSubject);
}

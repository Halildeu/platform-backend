package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.RemoteViewPolicyApprovalIntake;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RemoteViewPolicyApprovalIntakeRepository
        extends JpaRepository<RemoteViewPolicyApprovalIntake, UUID> {
    Optional<RemoteViewPolicyApprovalIntake> findByTenantIdAndApprovalId(UUID tenantId, UUID approvalId);
}

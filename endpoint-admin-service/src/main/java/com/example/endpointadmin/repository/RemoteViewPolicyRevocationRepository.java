package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.RemoteViewPolicyRevocation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface RemoteViewPolicyRevocationRepository extends JpaRepository<RemoteViewPolicyRevocation, UUID> {
    Optional<RemoteViewPolicyRevocation> findByTenantIdAndApprovalId(UUID tenantId, UUID approvalId);

    Optional<RemoteViewPolicyRevocation> findByTenantIdAndPublicationId(UUID tenantId, UUID publicationId);
}

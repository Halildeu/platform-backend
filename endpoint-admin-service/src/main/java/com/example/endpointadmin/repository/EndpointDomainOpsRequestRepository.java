package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointDomainOpsRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface EndpointDomainOpsRequestRepository extends JpaRepository<EndpointDomainOpsRequest, UUID> {

    Optional<EndpointDomainOpsRequest> findByTenantIdAndIdempotencyKeyHash(UUID tenantId,
                                                                           String idempotencyKeyHash);
}

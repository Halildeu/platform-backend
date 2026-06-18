package com.example.endpointadmin.repository;

import com.example.endpointadmin.domainops.DomainOpsStatus;
import com.example.endpointadmin.model.EndpointDomainOpsRequest;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EndpointDomainOpsRequestRepository extends JpaRepository<EndpointDomainOpsRequest, UUID> {

    Optional<EndpointDomainOpsRequest> findByTenantIdAndIdempotencyKeyHash(UUID tenantId,
                                                                           String idempotencyKeyHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
              from EndpointDomainOpsRequest r
             where r.id = :id
            """)
    Optional<EndpointDomainOpsRequest> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select r
              from EndpointDomainOpsRequest r
             where r.state in :states
               and r.expiresAt <= :now
             order by r.expiresAt asc
            """)
    List<EndpointDomainOpsRequest> findExpiredDispatchCandidates(@Param("states") Collection<DomainOpsStatus> states,
                                                                 @Param("now") Instant now,
                                                                 Pageable pageable);
}

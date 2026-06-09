package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRolloutFailureEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

/** #527 slice-1 — read access to the append-only event ledger. */
public interface EndpointRolloutFailureEventRepository extends JpaRepository<EndpointRolloutFailureEvent, UUID> {

    List<EndpointRolloutFailureEvent> findByTenantIdAndFailureIdOrderByCreatedAtAsc(UUID tenantId, UUID failureId);

    /** #527 §9.2 auto-ingest — source-result replay/double-listener idempotency guard. */
    boolean existsByTenantIdAndFailureIdAndSourceSignal(UUID tenantId, UUID failureId, String sourceSignal);
}

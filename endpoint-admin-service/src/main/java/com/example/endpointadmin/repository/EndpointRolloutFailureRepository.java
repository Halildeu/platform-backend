package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRolloutFailure;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * #527 slice-1 — read access to the failed-device rollout queue aggregate.
 * Org-scoped on tenant_id (= org_id canonical). A wave is bounded (50..800), so
 * the read service filters/counts in memory; no group-by query needed in v1.
 */
public interface EndpointRolloutFailureRepository extends JpaRepository<EndpointRolloutFailure, UUID> {

    Optional<EndpointRolloutFailure> findByTenantIdAndId(UUID tenantId, UUID id);

    List<EndpointRolloutFailure> findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(
            UUID tenantId, String rolloutId, String waveId);
}

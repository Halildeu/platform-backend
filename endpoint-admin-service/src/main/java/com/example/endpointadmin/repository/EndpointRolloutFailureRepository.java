package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRolloutFailure;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select f
            from EndpointRolloutFailure f
            where f.tenantId = :tenantId
              and f.id = :id
            """)
    Optional<EndpointRolloutFailure> findByTenantIdAndIdForUpdate(
            @Param("tenantId") UUID tenantId,
            @Param("id") UUID id);

    List<EndpointRolloutFailure> findByTenantIdAndRolloutIdAndWaveIdOrderByLastTransitionAtDesc(
            UUID tenantId, String rolloutId, String waveId);

    /**
     * #527 §9.2 auto-ingest — device-scoped lookup. The auto-ingest virtual wave
     * ({@code <failureClass>}) is NOT bounded like a real 50..800 rollout wave,
     * so the in-memory full-wave scan is replaced with a device-targeted query.
     */
    List<EndpointRolloutFailure> findByTenantIdAndRolloutIdAndWaveIdAndDeviceId(
            UUID tenantId, String rolloutId, String waveId, UUID deviceId);
}

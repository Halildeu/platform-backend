package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/** #527 §9.3 — the orchestrator wave/fleet metrics snapshot history (append-only). */
public interface EndpointRolloutWaveMetricsSnapshotRepository
        extends JpaRepository<EndpointRolloutWaveMetricsSnapshot, UUID> {

    /** Deterministic LATEST snapshot per (org, rollout, wave). */
    Optional<EndpointRolloutWaveMetricsSnapshot>
            findFirstByTenantIdAndRolloutIdAndWaveIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, String rolloutId, String waveId);
}

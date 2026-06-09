package com.example.endpointadmin.service;

import com.example.endpointadmin.dto.v1.admin.CreateWaveMetricsSnapshotRequest;
import com.example.endpointadmin.dto.v1.admin.WaveMetricsSnapshotResponse;
import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;
import com.example.endpointadmin.repository.EndpointRolloutWaveMetricsSnapshotRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Persists an orchestrator wave/fleet metrics snapshot (Faz 22.5 #527 §9.3).
 * Append-only history; the cross-field invariants (fleet ≥ wave, stale ≤ fleet)
 * + a future-skew guard on captured_at are validated for a clean 400 (the DB
 * CHECKs are the fail-loud backstop). source_type is server-fixed via the entity
 * @PrePersist; the principal proves "who".
 */
@Service
public class RolloutWaveMetricsSnapshotService {

    /** Tolerate small orchestrator/backend clock skew on captured_at. */
    private static final Duration FUTURE_SKEW_TOLERANCE = Duration.ofMinutes(5);

    private final EndpointRolloutWaveMetricsSnapshotRepository repository;

    public RolloutWaveMetricsSnapshotService(EndpointRolloutWaveMetricsSnapshotRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public WaveMetricsSnapshotResponse record(UUID tenantId, String rolloutId, String waveId,
                                              CreateWaveMetricsSnapshotRequest request) {
        if (request.fleetSize() < request.activeWaveSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "fleetSize must be >= activeWaveSize");
        }
        if (request.stale24hCount() > request.fleetSize()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "stale24hCount must be <= fleetSize");
        }
        if (request.capturedAt().isAfter(Instant.now().plus(FUTURE_SKEW_TOLERANCE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "capturedAt is in the future");
        }

        EndpointRolloutWaveMetricsSnapshot s = new EndpointRolloutWaveMetricsSnapshot();
        s.setId(UUID.randomUUID());
        s.setTenantId(tenantId);
        s.setRolloutId(rolloutId);
        s.setWaveId(waveId);
        s.setActiveWaveSize(request.activeWaveSize());
        s.setFleetSize(request.fleetSize());
        s.setStale24hCount(request.stale24hCount());
        s.setSourceSnapshotId(request.sourceSnapshotId());
        s.setCapturedAt(request.capturedAt());
        // org_id, created_at, source_type set by the entity @PrePersist.

        try {
            repository.saveAndFlush(s);
        } catch (DataIntegrityViolationException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "snapshot violates a metrics invariant");
        }
        return WaveMetricsSnapshotResponse.from(s);
    }
}

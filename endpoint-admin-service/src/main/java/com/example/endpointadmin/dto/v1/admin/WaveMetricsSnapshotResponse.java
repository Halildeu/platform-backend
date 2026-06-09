package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointRolloutWaveMetricsSnapshot;

import java.time.Instant;
import java.util.UUID;

/** 201 body for a recorded wave metrics snapshot (Faz 22.5 #527 §9.3). */
public record WaveMetricsSnapshotResponse(
        UUID id,
        String rolloutId,
        String waveId,
        int activeWaveSize,
        int fleetSize,
        int stale24hCount,
        String source,
        Instant capturedAt,
        Instant createdAt) {

    public static WaveMetricsSnapshotResponse from(EndpointRolloutWaveMetricsSnapshot s) {
        return new WaveMetricsSnapshotResponse(
                s.getId(), s.getRolloutId(), s.getWaveId(),
                s.getActiveWaveSize(), s.getFleetSize(), s.getStale24hCount(),
                s.getSourceType(), s.getCapturedAt(), s.getCreatedAt());
    }
}

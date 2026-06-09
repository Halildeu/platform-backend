package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointRolloutFailure;

import java.time.Instant;
import java.util.UUID;

/**
 * 201 body for a manual rollout-failure seed (Faz 22.5 #527 slice-1b). Returns
 * only the created resource's key identity + server-derived state; never the
 * tenant_id and never the raw evidence.
 */
public record RolloutFailureSeedResponse(
        UUID id,
        String rolloutId,
        String waveId,
        UUID deviceId,
        String failureClass,
        String currentState,
        String classifierVersion,
        Instant createdAt) {

    public static RolloutFailureSeedResponse from(EndpointRolloutFailure f) {
        return new RolloutFailureSeedResponse(
                f.getId(), f.getRolloutId(), f.getWaveId(), f.getDeviceId(),
                f.getCurrentClass().name(), f.getCurrentState().wire(),
                f.getClassifierVersion(), f.getCreatedAt());
    }
}

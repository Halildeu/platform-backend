package com.example.endpointadmin.service.rolloutfailure;

import com.example.endpointadmin.model.CommandType;

import java.util.UUID;

/**
 * Published (within {@code submitResult}'s transaction) when an agent command
 * result is persisted with a terminal NON-success status (FAILED/PARTIAL/
 * UNSUPPORTED). Consumed AFTER_COMMIT by {@link RolloutFailureAutoIngestListener}
 * to auto-classify + seed a rollout failed-device-queue item (Faz 22.5 #527
 * §9.2 slice-2a). Carries only stable identifiers; the mutable result fields are
 * re-loaded by the listener so it never reads a detached/uncommitted entity.
 */
public record CommandResultFailedEvent(
        UUID tenantId,
        UUID deviceId,
        UUID commandResultId,
        CommandType commandType) {
}

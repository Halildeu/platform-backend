package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.BackupDryrunRequestState;
import com.example.endpointadmin.model.EndpointBackupDryrunRequest;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Faz 22.8A.3b (#648) — PATH-FREE backup dry-run request view. Carries OPAQUE
 * {@code rootRefs} only (never a raw path); the dispatched command's payload is
 * separately redacted in the generic command DTO.
 */
public record AdminBackupDryrunRequestResponse(
        UUID id,
        UUID deviceId,
        BackupDryrunRequestState state,
        UUID commandId,
        String allowlistProfileId,
        boolean byod,
        List<String> rootRefs,
        String reason,
        String createdBy,
        String approvedBy,
        Instant createdAt,
        Instant stateUpdatedAt) {

    public static AdminBackupDryrunRequestResponse from(EndpointBackupDryrunRequest r) {
        return new AdminBackupDryrunRequestResponse(
                r.getId(),
                r.getDeviceId(),
                r.getState(),
                r.getCommandId(),
                r.getAllowlistProfileId(),
                r.isByod(),
                r.getRootsSnapshot().stream().map(s -> s.rootRef()).toList(),
                r.getReason(),
                r.getCreatedBy(),
                r.getApprovedBy(),
                r.getCreatedAt(),
                r.getStateUpdatedAt());
    }
}

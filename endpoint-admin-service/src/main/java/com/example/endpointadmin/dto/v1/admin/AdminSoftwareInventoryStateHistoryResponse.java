package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;

import java.time.Instant;
import java.util.UUID;

/**
 * BE-024 — summary projection used by the software-state history list
 * endpoint (Faz 22.5 software-inventory diff/history).
 *
 * <p>No {@code apps_digest} array on the wire so a paged history view does
 * not amplify into hundreds of app rows per capture (mirrors the BE-022Q
 * hardware summary discipline). Consumers that want the actual per-app
 * delta call the {@code /diff} route, which compares the latest two
 * captures server-side and returns only the changed apps.
 *
 * <p>{@code appsDigestHash} lets the UI cheaply tell two adjacent captures
 * apart (equal hash ⇒ a no-change re-collect).
 */
public record AdminSoftwareInventoryStateHistoryResponse(
        UUID id,
        UUID deviceId,
        Integer schemaVersion,
        Integer appCount,
        String appsDigestHash,
        Instant capturedAt,
        Instant createdAt
) {

    public static AdminSoftwareInventoryStateHistoryResponse from(
            EndpointSoftwareInventoryStateHistory history) {
        return new AdminSoftwareInventoryStateHistoryResponse(
                history.getId(),
                history.getDeviceId(),
                history.getSchemaVersion(),
                history.getAppCount(),
                history.getAppsDigestHash(),
                history.getCapturedAt(),
                history.getCreatedAt());
    }
}

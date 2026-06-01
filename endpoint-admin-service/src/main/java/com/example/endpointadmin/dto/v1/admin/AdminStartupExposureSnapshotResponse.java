package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointStartupExposureSnapshot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BE — full startup-exposure snapshot response (Faz 22.5, AG-040-be
 * query API). Mirrors AG-039-be {@code AdminServicesSnapshotResponse}
 * whitelist projection + carries the two extra flat exposure scalars
 * (rdpEnabled, windowsFirewallEventLogEnabled).
 */
public record AdminStartupExposureSnapshotResponse(
        UUID id,
        UUID tenantId,
        UUID deviceId,
        UUID sourceCommandResultId,
        Integer schemaVersion,
        Boolean supported,
        Boolean probeComplete,
        Boolean rdpEnabled,
        Boolean windowsFirewallEventLogEnabled,
        Integer probeDurationMs,
        String payloadHashSha256,
        Instant collectedAt,
        Instant createdAt,
        List<AdminStartupAppResponse> startupApps,
        List<AdminStartupExposureProbeErrorResponse> probeErrors) {

    public static AdminStartupExposureSnapshotResponse from(EndpointStartupExposureSnapshot s) {
        List<AdminStartupAppResponse> apps = new ArrayList<>();
        if (s.getStartupApps() != null) {
            for (var a : s.getStartupApps()) {
                apps.add(AdminStartupAppResponse.from(a));
            }
        }
        List<AdminStartupExposureProbeErrorResponse> errors = new ArrayList<>();
        if (s.getProbeErrors() != null) {
            for (var e : s.getProbeErrors()) {
                errors.add(AdminStartupExposureProbeErrorResponse.from(e));
            }
        }
        return new AdminStartupExposureSnapshotResponse(
                s.getId(),
                s.getTenantId(),
                s.getDeviceId(),
                s.getSourceCommandResultId(),
                s.getSchemaVersion(),
                s.getSupported(),
                s.getProbeComplete(),
                s.getRdpEnabled(),
                s.getWindowsFirewallEventLogEnabled(),
                s.getProbeDurationMs(),
                s.getPayloadHashSha256(),
                s.getCollectedAt(),
                s.getCreatedAt(),
                apps,
                errors);
    }
}

package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointStartupExposureApp;

/**
 * BE — per-startup-app response DTO for AG-040 startup-exposure
 * snapshot. Wire shape: {@code {name, location, enabled, probeOrigin}}.
 *
 * <p>HARD REDACTION: name is the registry value name / task name /
 * folder basename (extension stripped); location is the autorun anchor
 * enum (10 slots), NEVER full path. probeOrigin is REGISTRY vs
 * SCHEDULED_TASK source.
 */
public record AdminStartupAppResponse(
        Integer rowOrdinal,
        String name,
        String location,
        Boolean enabled,
        String probeOrigin) {

    public static AdminStartupAppResponse from(EndpointStartupExposureApp e) {
        return new AdminStartupAppResponse(
                e.getRowOrdinal(),
                e.getName(),
                e.getLocation(),
                e.getEnabled(),
                e.getProbeOrigin());
    }
}

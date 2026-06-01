package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointStartupExposureProbeError;

/**
 * BE — per-probe-error response DTO for AG-040 startup-exposure
 * snapshot. Wire shape: {@code {code, source?, summary?}}.
 */
public record AdminStartupExposureProbeErrorResponse(
        Integer rowOrdinal,
        String code,
        String source,
        String summary) {

    public static AdminStartupExposureProbeErrorResponse from(EndpointStartupExposureProbeError e) {
        return new AdminStartupExposureProbeErrorResponse(
                e.getRowOrdinal(),
                e.getCode(),
                e.getSource(),
                e.getSummary());
    }
}

package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import java.time.Instant;

/**
 * An orchestrator wave/fleet metrics snapshot write (Faz 22.5 #527 §9.3). The
 * authenticated deployment orchestrator supplies the rollout-scoped denominators
 * the backend cannot derive. Denominators must be positive (an undefined
 * percentage must never become available); cross-field invariants
 * (fleet ≥ wave, stale ≤ fleet) + future-skew are checked in the service.
 */
public record CreateWaveMetricsSnapshotRequest(
        @NotNull @Min(1) Integer activeWaveSize,
        @NotNull @Min(1) Integer fleetSize,
        @NotNull @Min(0) Integer stale24hCount,
        @NotNull Instant capturedAt,
        // bounded, opaque, non-PII orchestrator run ref (no whitespace/email/token shapes)
        @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._:-]{0,127}") String sourceSnapshotId) {
}

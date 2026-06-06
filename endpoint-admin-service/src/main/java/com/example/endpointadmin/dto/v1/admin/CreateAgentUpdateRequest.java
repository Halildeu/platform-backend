package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.DeploymentRing;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * BE-032 — request body for {@code POST /api/v1/admin/endpoint-devices/{deviceId}/agent-updates}.
 *
 * <p>The caller names a release catalog row; the backend resolves every
 * trust-sensitive wire field from the approved catalog. The caller never
 * supplies binary URLs, hashes, signer thumbprints, or signing tiers.
 */
public record CreateAgentUpdateRequest(
        @NotBlank
        @Size(max = 128)
        String releaseId,

        // Canonical key shape `admin-update-agent:{deviceId}:{releaseUuid}:{key}`
        // leaves 31 chars for the supplied key in endpoint_commands VARCHAR(128).
        // The service still hashes longer programmatic values defensively.
        @Size(max = 31)
        String idempotencyKey,

        @NotBlank
        @Size(max = 512)
        String reason,

        DeploymentRing requiredDeploymentRing,

        Instant notBefore,

        Instant expiresAt) {
}

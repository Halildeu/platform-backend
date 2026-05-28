package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * BE-021 — request body for {@code POST /api/v1/admin/endpoint-devices/{deviceId}/installs}.
 *
 * <p>The {@code catalogItemId} is the public slug (matches the BE-020
 * convention); the backend resolves the internal UUID + denormalized
 * package id from the catalog row. Caller-supplied {@code idempotencyKey}
 * is bounded so the canonical {@code admin-install:{deviceId}:{catalogUuid}:{key}}
 * string fits the {@code endpoint_commands.idempotency_key VARCHAR(128)}
 * column (Codex 019e6dfb iter-3 implementation note #3).
 */
public record CreateInstallRequest(
        @NotBlank
        @Size(max = 128)
        String catalogItemId,

        @Size(max = 48)
        String idempotencyKey,

        @Size(max = 512)
        String reason) {
}

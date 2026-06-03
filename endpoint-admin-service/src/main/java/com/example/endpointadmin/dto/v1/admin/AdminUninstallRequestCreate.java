package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AG-028 Phase 1b — request body for
 * {@code POST /api/v1/admin/endpoint-devices/{deviceId}/uninstalls}
 * (Faz 22.5.6).
 *
 * <p>The {@code catalogItemId} is the public slug (matches the BE-020
 * convention); the backend resolves the internal UUID + denormalized
 * package id from the catalog row.
 *
 * <p>Caller-supplied {@code idempotencyKey} is bounded so the canonical
 * {@code admin-uninstall:{deviceId(36)}:{catalogUuid(36)}:{key}} string
 * fits the {@code endpoint_commands.idempotency_key VARCHAR(128)}
 * column (Phase 1a V32 + parity with the install path).
 */
public record AdminUninstallRequestCreate(
        @NotBlank
        @Size(max = 128)
        String catalogItemId,

        // Canonical idempotency key
        // `admin-uninstall:{deviceId(36)}:{catalogUuid(36)}:{body}` has
        // a fixed prefix of 90 characters. The DB column is VARCHAR(128),
        // so the supplied key must fit in 40 characters; anything
        // longer is SHA-256-prefix-hashed by the service layer.
        @Size(max = 40)
        String idempotencyKey,

        @Size(max = 512)
        String reason) {
}

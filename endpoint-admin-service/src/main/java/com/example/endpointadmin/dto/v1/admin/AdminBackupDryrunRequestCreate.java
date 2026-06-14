package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Faz 22.8A.3b (#648) — propose a backup dry-run. The scope is given by OPAQUE
 * {@code rootRefs} (resolved against the 22.8A.3a registry; the admin never
 * supplies a raw path). {@code reason} is REQUIRED (privacy-sensitive
 * maker-checker) and is path-free scanned server-side. {@code byod} is forced
 * false this slice.
 */
public record AdminBackupDryrunRequestCreate(
        @NotEmpty @Size(max = 64) List<@NotBlank @Size(max = 255) String> rootRefs,
        @NotBlank @Size(max = 255) String allowlistProfileId,
        @NotBlank @Size(max = 512) String reason,
        @Size(max = 128) String idempotencyKey) {
}

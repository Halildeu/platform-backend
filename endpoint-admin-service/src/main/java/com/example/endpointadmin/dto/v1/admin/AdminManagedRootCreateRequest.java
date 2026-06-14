package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Faz 22.8A.3a (#648) — register a managed-data-root in the backup dry-run
 * registry. {@code rootRef} is the opaque reference surfaced everywhere;
 * {@code localPath} is the raw managed-root path stored INTERNAL-ONLY (never
 * echoed back in a response or audit). {@code pathClass} / {@code rootRef} are
 * re-validated server-side (bounded enum + opaque token charset).
 */
public record AdminManagedRootCreateRequest(
        @NotBlank @Size(max = 255) String rootRef,
        @NotBlank @Size(max = 64) String pathClass,
        @NotBlank @Size(max = 4096) String localPath,
        @NotNull Boolean companyManaged) {
}

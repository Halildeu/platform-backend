package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Faz 22.8A.3a (#648) — enable/disable a registry root. Disabling makes the
 * root unresolvable by the issuing surface (fail-closed). Optional reason is
 * recorded for the operator trail (path-free).
 */
public record AdminManagedRootSetEnabledRequest(
        @NotNull Boolean enabled,
        @Size(max = 512) String reason) {
}

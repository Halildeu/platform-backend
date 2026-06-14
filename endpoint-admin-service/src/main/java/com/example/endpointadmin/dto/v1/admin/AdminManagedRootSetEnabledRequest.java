package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotNull;

/**
 * Faz 22.8A.3a (#648) — enable/disable a registry root. Disabling makes the
 * root unresolvable by the issuing surface (fail-closed). The actor of the
 * change is recorded path-free as {@code updated_by} on the entity; no
 * free-text reason is captured in this slice (a free-text note could carry a
 * raw path; a path-free-scanned audit note is a 22.8A.3b / audit follow-up).
 */
public record AdminManagedRootSetEnabledRequest(
        @NotNull Boolean enabled) {
}

package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * AG-028 Phase 0 — request body for
 * {@code POST /api/v1/admin/catalog-items/{id}/uninstall-settings-change/{rid}/reject}.
 *
 * <p>Rejection body MUST carry a non-blank reason (DB CHECK invariant:
 * REJECTED state requires {@code reject_reason} populated).
 */
public record AdminCatalogUninstallSettingsChangeRequestRejection(
        @NotBlank @Size(max = 2000) String rejectReason
) {
}

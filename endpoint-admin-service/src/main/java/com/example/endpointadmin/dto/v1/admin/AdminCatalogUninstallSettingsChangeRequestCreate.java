package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CatalogUninstallSettingsField;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * AG-028 Phase 0 — request body for
 * {@code POST /api/v1/admin/catalog-items/{id}/uninstall-settings-change}.
 *
 * <p>Caller proposes a flip of one uninstall flag on an APPROVED catalog
 * row. Approval lands separately (different caller) per maker-checker
 * invariant.
 */
public record AdminCatalogUninstallSettingsChangeRequestCreate(
        @NotNull CatalogUninstallSettingsField field,
        @NotNull Boolean newValue,
        @Size(max = 2000) String reason
) {
}

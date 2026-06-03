package com.example.endpointadmin.dto.v1.admin;

import jakarta.validation.constraints.Size;

/**
 * AG-028 Phase 0 — request body for
 * {@code POST /api/v1/admin/catalog-items/{id}/uninstall-settings-change/{rid}/approve}.
 *
 * <p>Approval body carries only an optional rationale; the approver
 * subject is taken from the authenticated principal. Maker-checker
 * invariant (approver ≠ proposer) is enforced at the service layer
 * AND at the DB CHECK.
 */
public record AdminCatalogUninstallSettingsChangeRequestApproval(
        @Size(max = 2000) String reason
) {
}

package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequest;
import com.example.endpointadmin.model.CatalogUninstallSettingsChangeRequestState;
import com.example.endpointadmin.model.CatalogUninstallSettingsField;

import java.time.Instant;
import java.util.UUID;

/**
 * AG-028 Phase 0 — response projection for a
 * {@link CatalogUninstallSettingsChangeRequest}.
 *
 * <p>Used by the admin REST surface (propose/approve/reject/list/get
 * endpoints) and the catalog drawer "Yönetim Hakları" panel in
 * platform-web.
 */
public record AdminCatalogUninstallSettingsChangeRequestResponse(
        UUID id,
        UUID tenantId,
        UUID catalogItemId,
        CatalogUninstallSettingsField field,
        boolean newValue,
        String proposedBy,
        Instant proposedAt,
        String approvedBy,
        Instant approvedAt,
        Instant appliedAt,
        CatalogUninstallSettingsChangeRequestState state,
        String rejectReason,
        String reason,
        Long version,
        Instant createdAt
) {
    public static AdminCatalogUninstallSettingsChangeRequestResponse from(
            CatalogUninstallSettingsChangeRequest e) {
        return new AdminCatalogUninstallSettingsChangeRequestResponse(
                e.getId(),
                e.getTenantId(),
                e.getCatalogItemId(),
                e.getField(),
                e.isNewValue(),
                e.getProposedBy(),
                e.getProposedAt(),
                e.getApprovedBy(),
                e.getApprovedAt(),
                e.getAppliedAt(),
                e.getState(),
                e.getRejectReason(),
                e.getReason(),
                e.getVersion(),
                e.getCreatedAt()
        );
    }
}

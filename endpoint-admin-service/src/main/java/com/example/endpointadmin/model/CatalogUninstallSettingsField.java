package com.example.endpointadmin.model;

/**
 * AG-028 Phase 0 — fields that can be flipped via the
 * {@link CatalogUninstallSettingsChangeRequest} flow.
 *
 * <p>Codex iter-2 absorb: catalog approved-row flag flips bypass the
 * existing DRAFT→APPROVED maker-checker if applied via direct PATCH.
 * This enum defines the closed set of fields routed through the
 * change-request flow.
 *
 * <p>V31 DB CHECK enforces {@code field IN ('UNINSTALL_SUPPORTED',
 * 'UNINSTALL_PROTECTED')}. The DB stores the JPA enum name (uppercase),
 * matching {@code @Enumerated(EnumType.STRING)} default semantics.
 */
public enum CatalogUninstallSettingsField {
    UNINSTALL_SUPPORTED,
    UNINSTALL_PROTECTED
}

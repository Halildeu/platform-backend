package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.SoftwareInventoryChangeType;

/**
 * BE-024 — one app-level change between the latest two software-inventory
 * captures (Faz 22.5 software-inventory diff).
 *
 * <p>Whitelist projection — only the diff-relevant fields are exposed:
 * <ul>
 *   <li>{@code appKey} — SYNTHETIC stable identity (SHA-256 over
 *       {@code lower(displayName)|lower(publisher)|msiProductCodeHash});
 *       NOT the winget catalog packageId. Inventory items carry no
 *       packageId column, so a natural-key hash is the diff identity.</li>
 *   <li>{@code displayName} / {@code publisher} — carried for human
 *       readability (the appKey alone is opaque).</li>
 *   <li>{@code fromVersion} — version in the previous capture; {@code null}
 *       for {@link SoftwareInventoryChangeType#ADDED}.</li>
 *   <li>{@code toVersion} — version in the latest capture; {@code null} for
 *       {@link SoftwareInventoryChangeType#REMOVED}.</li>
 *   <li>{@code changeType} — ADDED / REMOVED / VERSION_CHANGED.</li>
 * </ul>
 *
 * <p>No user path, install log, uninstall string, or raw MSI GUID is ever
 * present (the source data was fail-closed sanitized at ingest).
 */
public record AdminSoftwareInventoryDiffEntryResponse(
        String appKey,
        String displayName,
        String publisher,
        String fromVersion,
        String toVersion,
        SoftwareInventoryChangeType changeType
) {

    public static AdminSoftwareInventoryDiffEntryResponse added(
            String appKey, String displayName, String publisher, String toVersion) {
        return new AdminSoftwareInventoryDiffEntryResponse(
                appKey, displayName, publisher, null, toVersion,
                SoftwareInventoryChangeType.ADDED);
    }

    public static AdminSoftwareInventoryDiffEntryResponse removed(
            String appKey, String displayName, String publisher, String fromVersion) {
        return new AdminSoftwareInventoryDiffEntryResponse(
                appKey, displayName, publisher, fromVersion, null,
                SoftwareInventoryChangeType.REMOVED);
    }

    public static AdminSoftwareInventoryDiffEntryResponse versionChanged(
            String appKey, String displayName, String publisher,
            String fromVersion, String toVersion) {
        return new AdminSoftwareInventoryDiffEntryResponse(
                appKey, displayName, publisher, fromVersion, toVersion,
                SoftwareInventoryChangeType.VERSION_CHANGED);
    }
}

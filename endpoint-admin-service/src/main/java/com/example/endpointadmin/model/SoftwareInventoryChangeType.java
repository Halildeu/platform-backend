package com.example.endpointadmin.model;

/**
 * BE-024 — kind of change for a single app between two consecutive
 * software-inventory captures (Faz 22.5 software-inventory diff).
 *
 * <p>STRICT v1 scope: only these three. "Outdated / availableVersion"
 * deltas are explicitly OUT of scope (BE-024b — they depend on AG-036
 * winget-upgrade data + the BE-023 catalog, not yet available). There is
 * deliberately NO {@code OUTDATED} member here so a future widening cannot
 * sneak in via this enum without an explicit migration.
 */
public enum SoftwareInventoryChangeType {

    /** Present in the latest capture, absent in the previous one. */
    ADDED,

    /** Present in the previous capture, absent in the latest one. */
    REMOVED,

    /** Present in both captures under the same appKey, different version. */
    VERSION_CHANGED
}

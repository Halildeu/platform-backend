package com.example.endpointadmin.model;

/**
 * Faz 22.8A.3b (#648) — a path-free propose-time snapshot of one managed root:
 * its OPAQUE {@code rootRef} + the registry {@code rootVersion} at propose time.
 * Approve revalidates each root's CURRENT registry root_version against this
 * snapshot (drift → 409/422 re-propose). No raw path — opaque ref + integer only.
 */
public record BackupDryrunRootSnapshot(String rootRef, int rootVersion) {
}

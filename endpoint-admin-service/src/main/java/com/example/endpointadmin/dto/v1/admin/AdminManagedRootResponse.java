package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.model.EndpointBackupDryrunManagedRoot;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 22.8A.3a (#648) — PATH-FREE managed-root view. Deliberately OMITS
 * {@code localPath}: the raw managed-root path is internal-only and must never
 * leave the backend in an admin response (Codex 019ec45e — data minimization
 * extends to the issuing layer, not just the manifest).
 */
public record AdminManagedRootResponse(
        UUID id,
        String rootRef,
        String pathClass,
        boolean companyManaged,
        boolean enabled,
        int rootVersion,
        String createdBy,
        Instant createdAt,
        Instant updatedAt) {

    public static AdminManagedRootResponse from(EndpointBackupDryrunManagedRoot r) {
        return new AdminManagedRootResponse(
                r.getId(),
                r.getRootRef(),
                r.getPathClass(),
                r.isCompanyManaged(),
                r.isEnabled(),
                r.getRootVersion(),
                r.getCreatedBy(),
                r.getCreatedAt(),
                r.getUpdatedAt());
    }
}

package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareDiffCacheRow;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * BE-024c software diff summary cache repository (Faz 22.5 P2-A v2-c-pre,
 * Codex 019e88b5 iter-5 AGREE). Read-only JPA in this PR; the race-safe
 * native UPSERT writer arrives in v2-c-pre-2 alongside the ingest hooks +
 * backfill (Codex iter-2 UPSERT pattern).
 *
 * <p>One canonical row per {@code (orgId, deviceId)} per the V35 UNIQUE
 * (Faz 21.1 C2a; org_id = tenantId canonically; the V27 tenant-keyed UNIQUE
 * was dropped by V35's atomic swap so a single ON CONFLICT arbiter exists).
 */
@Repository
public interface EndpointSoftwareDiffCacheRepository
        extends JpaRepository<EndpointSoftwareDiffCacheRow, UUID> {

    // Faz 21.1 C2a (Codex 019e919e): cache identity is org-keyed (V35 UNIQUE
    // (org_id, device_id)). org_id = tenantId canonically.
    Optional<EndpointSoftwareDiffCacheRow> findByOrgIdAndDeviceId(UUID orgId, UUID deviceId);

    long countByTenantId(UUID tenantId);
}

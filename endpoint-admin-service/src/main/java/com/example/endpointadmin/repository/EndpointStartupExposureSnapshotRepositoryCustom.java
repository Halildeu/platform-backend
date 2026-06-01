package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointStartupExposureSnapshot;

import java.util.UUID;

/**
 * BE — custom write path for the append-only startup-exposure snapshot
 * (Faz 22.5, AG-040-be ingest). Mirrors AG-039-be
 * {@code EndpointServicesSnapshotRepositoryCustom} targetless
 * ON CONFLICT pattern. Dual idempotency: partial UNIQUE on
 * source_command_result_id + full UNIQUE on (tenant, device, hash).
 */
public interface EndpointStartupExposureSnapshotRepositoryCustom {
    UUID insertStartupExposureSnapshotOnConflictDoNothing(EndpointStartupExposureSnapshot snapshot);
}

package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointHardwareInventorySnapshot;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * BE-022 — read access for hardware inventory snapshots.
 *
 * <p>Latest-per-device query uses the
 * {@code idx_endpoint_hardware_inventory_snapshots_tenant_device_time}
 * composite index for index-only scans. The deterministic
 * tiebreaker — {@code collected_at DESC, created_at DESC, id DESC} —
 * matches the index column order so PG can avoid a sort.
 */
@Repository
public interface EndpointHardwareInventorySnapshotRepository
        extends JpaRepository<EndpointHardwareInventorySnapshot, UUID> {

    /**
     * Idempotency probe for the agent SUBMIT-result hook: if the
     * command-result already produced a snapshot, return it instead of
     * creating a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call.
     */
    Optional<EndpointHardwareInventorySnapshot> findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Latest snapshot per (tenant, device). Composite index lookup +
     * deterministic ordering (Codex 019e7007 iter-3 nice_to_have).
     */
    Optional<EndpointHardwareInventorySnapshot>
            findFirstByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId);

    /** Append-only history per (tenant, device). */
    Page<EndpointHardwareInventorySnapshot> findByTenantIdAndDeviceIdOrderByCollectedAtDescCreatedAtDescIdDesc(
            UUID tenantId, UUID deviceId, Pageable pageable);
}

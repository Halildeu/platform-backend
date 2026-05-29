package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-024 — read/append access for the append-only software-state history.
 *
 * <p>The diff service loads the latest two captures per (tenant, device)
 * with {@link #findByTenantIdAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc}
 * (capped to {@code PageRequest.of(0, 2)}); the history view pages the same
 * ordering. The deterministic tiebreaker — {@code captured_at DESC,
 * created_at DESC, id DESC} — matches the
 * {@code idx_endpoint_software_inventory_state_history_tenant_dev_time}
 * composite index column order so PG avoids a sort.
 */
@Repository
public interface EndpointSoftwareInventoryStateHistoryRepository
        extends JpaRepository<EndpointSoftwareInventoryStateHistory, UUID> {

    /**
     * Idempotency probe for the inline ingest append: if the
     * command-result already produced a capture, the service no-ops
     * instead of appending a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call.
     */
    Optional<EndpointSoftwareInventoryStateHistory>
            findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Latest-then-history captures per (tenant, device). The diff service
     * takes the head two with {@code PageRequest.of(0, 2)}. Composite-index
     * lookup + deterministic ordering ({@code captured_at DESC,
     * created_at DESC, id DESC}).
     */
    List<EndpointSoftwareInventoryStateHistory>
            findByTenantIdAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                    UUID tenantId, UUID deviceId, Pageable pageable);

    /**
     * Paged append-only history per (tenant, device) for the history view.
     * Ordering is supplied by the caller-built {@link Pageable} {@code Sort}
     * (the controller pins {@code captured_at DESC, created_at DESC, id DESC}
     * so it matches the composite index).
     */
    Page<EndpointSoftwareInventoryStateHistory>
            findByTenantIdAndDeviceId(UUID tenantId, UUID deviceId, Pageable pageable);
}

package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.EndpointSoftwareInventoryStateHistory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * BE-024 — read/append access for the append-only software-state history.
 *
 * <p>The diff service loads the latest two captures per (org, device) with
 * {@link #findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc}
 * (capped to {@code PageRequest.of(0, 2)}); the history view pages the same
 * ordering. The deterministic tiebreaker — {@code captured_at DESC,
 * created_at DESC, id DESC} — matches the
 * {@code idx_endpoint_software_inventory_state_history_tenant_dev_time}
 * composite index column order ({@code tenant_id, device_id, captured_at,
 * created_at, id}) so PG can satisfy the top-N scan without a sort.
 *
 * <p>Faz 21.1 PR2b-iv.c — both read methods migrated from derived
 * {@code findByTenantIdAndDeviceId*} to explicit {@code @Query} with the
 * canonical effective-org filter (Codex 019e8d1d B-C sub-slice AGREE +
 * 019e8dbb post-impl REVISE absorb #1 — index-friendly form). The predicate
 * keeps the {@code tenant_id = :orgId} branch explicit so the
 * (tenant_id, device_id, captured_at, created_at, id) composite index
 * remains usable; the orgId OR-fallback is layered on top:
 *
 * <pre>
 *   WHERE h.tenant_id = :orgId
 *     AND (h.org_id = :orgId OR h.org_id IS NULL)
 *     AND h.device_id = :deviceId
 * </pre>
 *
 * <p>This is semantically equivalent to the original effective-org form
 * under V30's {@code CHECK (org_id IS NULL OR org_id = tenant_id)}: every
 * row with {@code org_id = X} also has {@code tenant_id = X}, so adding
 * the explicit {@code tenant_id = :orgId} predicate cannot drop a row.
 * The {@code orgId} parameter is the caller's canonical tenant scope
 * (= legacy {@code tenantId}). Defensive against legacy NULL rows
 * (V29 trigger normally back-fills but the OR branch guarantees
 * correctness independent of the trigger).
 *
 * <p>The third method, {@link #findBySourceCommandResultId(UUID)}, is NOT
 * migrated — it is the idempotency probe for the inline ingest append and
 * the partial UNIQUE index on {@code source_command_result_id} already
 * guarantees per-result uniqueness independent of tenant/org scope.
 */
@Repository
public interface EndpointSoftwareInventoryStateHistoryRepository
        extends JpaRepository<EndpointSoftwareInventoryStateHistory, UUID>,
        EndpointSoftwareInventoryStateHistoryRepositoryCustom {

    /**
     * Idempotency probe for the inline ingest append: if the
     * command-result already produced a capture, the service no-ops
     * instead of appending a duplicate. The partial UNIQUE index on
     * {@code source_command_result_id} backs this call. NOT migrated to
     * the effective-org filter — the command-result id is the unique key
     * by itself and the index is already partial.
     */
    Optional<EndpointSoftwareInventoryStateHistory>
            findBySourceCommandResultId(UUID sourceCommandResultId);

    /**
     * Canonical PR2b-iv.c read — latest-then-history captures per (org,
     * device) with the effective-org filter in index-friendly form
     * (Codex 019e8dbb post-impl REVISE absorb #1). The diff service takes
     * the head two with {@code PageRequest.of(0, 2)}. The
     * {@code tenant_id = :orgId} predicate keeps the
     * (tenant_id, device_id, captured_at, created_at, id) composite
     * index usable for the top-N scan; V30 CHECK guarantees the
     * orgId OR-fallback is semantically equivalent.
     */
    @Query("""
            select h
            from EndpointSoftwareInventoryStateHistory h
            where h.tenantId = :orgId
              and (h.orgId = :orgId or h.orgId is null)
              and h.deviceId = :deviceId
            order by h.capturedAt desc, h.createdAt desc, h.id desc
            """)
    List<EndpointSoftwareInventoryStateHistory>
            findVisibleToOrgAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc(
                    @Param("orgId") UUID orgId,
                    @Param("deviceId") UUID deviceId,
                    Pageable pageable);

    /**
     * Canonical PR2b-iv.c read — paged append-only history per (org,
     * device) with the effective-org filter in index-friendly form,
     * ordering supplied by the caller-built {@link Pageable} {@code Sort}
     * (the controller pins {@code captured_at DESC, created_at DESC,
     * id DESC} so it matches the composite index). {@code countQuery}
     * sibling computes total over the same predicate. Replaces the
     * pre-PR2b-iv {@code findByTenantIdAndDeviceId} derived method.
     */
    @Query(value = """
            select h
            from EndpointSoftwareInventoryStateHistory h
            where h.tenantId = :orgId
              and (h.orgId = :orgId or h.orgId is null)
              and h.deviceId = :deviceId
            """,
            countQuery = """
            select count(h)
            from EndpointSoftwareInventoryStateHistory h
            where h.tenantId = :orgId
              and (h.orgId = :orgId or h.orgId is null)
              and h.deviceId = :deviceId
            """)
    Page<EndpointSoftwareInventoryStateHistory>
            findVisibleToOrgAndDeviceId(
                    @Param("orgId") UUID orgId,
                    @Param("deviceId") UUID deviceId,
                    Pageable pageable);
}

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
 * composite index column order so PG avoids a sort.
 *
 * <p>Faz 21.1 PR2b-iv.c — both read methods migrated from derived
 * {@code findByTenantIdAndDeviceId*} to explicit {@code @Query} with the
 * canonical effective-org filter (Codex 019e8d1d B-C sub-slice AGREE;
 * P1 parenthesized OR pattern). Accepts both canonical rows (post-PR2b-ii:
 * {@code org_id = tenant_id}) and legacy rows ({@code org_id IS NULL AND
 * tenant_id = :orgId}, defensive — V29 trigger normally back-fills but the
 * OR branch guarantees correctness independent of the trigger). The
 * {@code orgId} parameter is the caller's canonical tenant scope (= legacy
 * {@code tenantId}); V30 CHECK guarantees a written row's {@code org_id}
 * matches its {@code tenant_id} when both are populated.
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
     * device) with the effective-org filter. The diff service takes the
     * head two with {@code PageRequest.of(0, 2)}. Composite-index lookup
     * + deterministic ordering ({@code captured_at DESC, created_at DESC,
     * id DESC}). Replaces the pre-PR2b-iv
     * {@code findByTenantIdAndDeviceIdOrderByCapturedAtDescCreatedAtDescIdDesc}
     * derived method.
     */
    @Query("""
            select h
            from EndpointSoftwareInventoryStateHistory h
            where (h.orgId = :orgId or (h.orgId is null and h.tenantId = :orgId))
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
     * device) with the effective-org filter, ordering supplied by the
     * caller-built {@link Pageable} {@code Sort} (the controller pins
     * {@code captured_at DESC, created_at DESC, id DESC} so it matches
     * the composite index). Replaces the pre-PR2b-iv
     * {@code findByTenantIdAndDeviceId} derived method.
     */
    @Query(value = """
            select h
            from EndpointSoftwareInventoryStateHistory h
            where (h.orgId = :orgId or (h.orgId is null and h.tenantId = :orgId))
              and h.deviceId = :deviceId
            """,
            countQuery = """
            select count(h)
            from EndpointSoftwareInventoryStateHistory h
            where (h.orgId = :orgId or (h.orgId is null and h.tenantId = :orgId))
              and h.deviceId = :deviceId
            """)
    Page<EndpointSoftwareInventoryStateHistory>
            findVisibleToOrgAndDeviceId(
                    @Param("orgId") UUID orgId,
                    @Param("deviceId") UUID deviceId,
                    Pageable pageable);
}

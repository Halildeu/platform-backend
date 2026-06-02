package com.example.endpointadmin.repository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Faz 22.7 D2 (Codex 019e881c AGREE D): cross-snapshot compliance gap
 * aggregation native PG queries.
 *
 * <p>Design constraint: each query uses {@code DISTINCT ON (device_id, tenant_id)}
 * to pick the latest snapshot per device within the freshness window, then
 * LEFT JOINs against {@code endpoint_devices} to enrich + filter.
 *
 * <p>Per-gap-type predicates evaluated server-side; only devices with
 * AT LEAST ONE active gap matching the requested {@code gapTypes} set are
 * returned. Bounded by {@code pageSize} (max 200 enforced by service).
 *
 * <p>Tenant scoping enforced via {@code :tenantId} bind on every join.
 * HARD RULE No Fake Work: NO client-side filtering over fetched rows.
 */
@Repository
public class ComplianceGapRepository {

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Find devices with at least one active gap matching the requested types.
     *
     * @return rows: [device_id (UUID), hostname, display_name, rdp_enabled (Boolean),
     *               startup_collected_at (Timestamp), pending_total_count (Integer),
     *               hotfix_collected_at (Timestamp)]
     */
    public List<Object[]> findGapDevices(UUID tenantId,
                                         Instant freshnessThreshold,
                                         Set<String> gapTypeWires,
                                         int limit,
                                         int offset) {
        String sql = """
                WITH latest_startup AS (
                    SELECT DISTINCT ON (device_id, tenant_id)
                        device_id, tenant_id, rdp_enabled, collected_at
                    FROM endpoint_startup_exposure_snapshots
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY device_id, tenant_id, collected_at DESC
                ),
                latest_hotfix AS (
                    SELECT DISTINCT ON (device_id, tenant_id)
                        device_id, tenant_id, pending_total_count, collected_at
                    FROM endpoint_hotfix_posture_snapshots
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY device_id, tenant_id, collected_at DESC
                )
                SELECT
                    d.id AS device_id,
                    d.hostname,
                    d.display_name,
                    s.rdp_enabled,
                    s.collected_at AS startup_collected_at,
                    h.pending_total_count,
                    h.collected_at AS hotfix_collected_at
                FROM endpoint_devices d
                LEFT JOIN latest_startup s ON s.device_id = d.id AND s.tenant_id = d.tenant_id
                LEFT JOIN latest_hotfix h ON h.device_id = d.id AND h.tenant_id = d.tenant_id
                WHERE d.tenant_id = :tenantId
                  AND (
                    (:rdpEnabledRequested = TRUE AND s.rdp_enabled = TRUE)
                    OR
                    (:pendingUpdatesRequested = TRUE AND h.pending_total_count > 0)
                  )
                ORDER BY GREATEST(
                    COALESCE(s.collected_at, '1970-01-01'::timestamp),
                    COALESCE(h.collected_at, '1970-01-01'::timestamp)
                ) DESC, d.hostname ASC
                LIMIT :limit OFFSET :offset
                """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("freshnessThreshold", Timestamp.from(freshnessThreshold));
        query.setParameter("rdpEnabledRequested",
                gapTypeWires.contains("rdp_enabled"));
        query.setParameter("pendingUpdatesRequested",
                gapTypeWires.contains("pending_security_updates"));
        query.setParameter("limit", limit);
        query.setParameter("offset", offset);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();
        return rows;
    }

    /**
     * Count of devices matching the gap criteria. Mirrors
     * {@link #findGapDevices} WHERE clause without LIMIT/OFFSET.
     */
    public long countGapDevices(UUID tenantId,
                                Instant freshnessThreshold,
                                Set<String> gapTypeWires) {
        String sql = """
                WITH latest_startup AS (
                    SELECT DISTINCT ON (device_id, tenant_id)
                        device_id, tenant_id, rdp_enabled, collected_at
                    FROM endpoint_startup_exposure_snapshots
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY device_id, tenant_id, collected_at DESC
                ),
                latest_hotfix AS (
                    SELECT DISTINCT ON (device_id, tenant_id)
                        device_id, tenant_id, pending_total_count, collected_at
                    FROM endpoint_hotfix_posture_snapshots
                    WHERE tenant_id = :tenantId AND collected_at >= :freshnessThreshold
                    ORDER BY device_id, tenant_id, collected_at DESC
                )
                SELECT COUNT(*)
                FROM endpoint_devices d
                LEFT JOIN latest_startup s ON s.device_id = d.id AND s.tenant_id = d.tenant_id
                LEFT JOIN latest_hotfix h ON h.device_id = d.id AND h.tenant_id = d.tenant_id
                WHERE d.tenant_id = :tenantId
                  AND (
                    (:rdpEnabledRequested = TRUE AND s.rdp_enabled = TRUE)
                    OR
                    (:pendingUpdatesRequested = TRUE AND h.pending_total_count > 0)
                  )
                """;

        Query query = entityManager.createNativeQuery(sql);
        query.setParameter("tenantId", tenantId);
        query.setParameter("freshnessThreshold", Timestamp.from(freshnessThreshold));
        query.setParameter("rdpEnabledRequested",
                gapTypeWires.contains("rdp_enabled"));
        query.setParameter("pendingUpdatesRequested",
                gapTypeWires.contains("pending_security_updates"));

        Number count = (Number) query.getSingleResult();
        return count.longValue();
    }
}

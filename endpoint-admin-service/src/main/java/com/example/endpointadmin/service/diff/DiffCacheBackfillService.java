package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.service.EndpointOutdatedSoftwareDiffService;
import com.example.endpointadmin.service.EndpointSoftwareInventoryDiffService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-024c v2-c-pre-2-C-B {@link DiffCacheBackfillService} — explicit
 * tenant-scoped sweep that catches up cache rows missed by the AFTER_COMMIT
 * listener path (e.g. devices ingested before v2-c-pre-2-C-A landed, or
 * cache rows lagged by a listener failure that the listener's catch-log
 * swallowed).
 *
 * <p>Each per-device refresh runs in {@link Propagation#REQUIRES_NEW} so a
 * failure on one device does not abort the rest of the batch. The writer
 * source-pair guard from v2-c-pre-2-C-A keeps the sweep idempotent — a
 * cache row already at the latest source tuple stays untouched and is
 * counted as {@code skippedStale} (zero-churn).
 *
 * <p>Codex 019e89e8 iter-5 AGREE on split: this is the v2-c-pre-2-C-B
 * scope; the AFTER_COMMIT listener path from v2-c-pre-2-C-A handles the
 * real-time refresh, the backfill handles the catch-up.
 */
@Service
public class DiffCacheBackfillService {

    private static final Logger log = LoggerFactory.getLogger(DiffCacheBackfillService.class);

    private final EndpointSoftwareInventoryDiffService softwareDiffService;
    private final EndpointOutdatedSoftwareDiffService outdatedDiffService;
    private final DiffCacheService diffCacheService;
    private final JdbcTemplate jdbc;

    public DiffCacheBackfillService(
            EndpointSoftwareInventoryDiffService softwareDiffService,
            EndpointOutdatedSoftwareDiffService outdatedDiffService,
            DiffCacheService diffCacheService,
            JdbcTemplate jdbc) {
        this.softwareDiffService = softwareDiffService;
        this.outdatedDiffService = outdatedDiffService;
        this.diffCacheService = diffCacheService;
        this.jdbc = jdbc;
    }

    /**
     * Backfill the diff cache for an explicit list of devices in one
     * tenant. Each device runs in its own {@code REQUIRES_NEW} via
     * {@link #refreshOneDevice}, so a failure on one device does not
     * propagate to the rest.
     */
    public DiffCacheBackfillResult backfillBatch(
            UUID tenantId, DiffType type, List<UUID> deviceIds) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(deviceIds, "deviceIds");
        long start = System.nanoTime();
        long checked = 0L;
        long changed = 0L;
        long skippedStale = 0L;
        long errors = 0L;
        for (UUID deviceId : deviceIds) {
            checked++;
            try {
                boolean wrote = refreshOneDevice(tenantId, deviceId, type);
                if (wrote) {
                    changed++;
                } else {
                    skippedStale++;
                }
            } catch (RuntimeException ex) {
                errors++;
                // Redacted log per Codex iter-3 plan-time direction:
                // tenant/device/type/error class+message only.
                log.warn("DiffCache backfill failed tenant={} device={} type={} error={}: {}",
                        tenantId, deviceId, type,
                        ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        return new DiffCacheBackfillResult(checked, changed, skippedStale, errors, elapsedMs);
    }

    /**
     * Backfill all devices in a tenant for the given type. Pages through
     * {@code endpoint_devices} so the worker batch size cap stays explicit
     * (no streaming of unbounded result sets into memory).
     */
    public DiffCacheBackfillResult backfillTenant(
            UUID tenantId, DiffType type, int pageSize) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(type, "type");
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("pageSize out of range [1, 5000]: " + pageSize);
        }
        DiffCacheBackfillResult acc = DiffCacheBackfillResult.empty();
        int offset = 0;
        while (true) {
            List<UUID> page = jdbc.query(
                    "SELECT id FROM endpoint_admin_service.endpoint_devices "
                    + "WHERE tenant_id = ? "
                    + "ORDER BY id "
                    + "LIMIT ? OFFSET ?",
                    (rs, i) -> (UUID) rs.getObject("id"),
                    tenantId, pageSize, offset);
            if (page.isEmpty()) {
                break;
            }
            DiffCacheBackfillResult batch = backfillBatch(tenantId, type, page);
            acc = acc.plus(batch);
            if (page.size() < pageSize) {
                break;
            }
            offset += page.size();
        }
        return acc;
    }

    /**
     * Refresh exactly one device's cache row for one type. Runs in its
     * own {@link Propagation#REQUIRES_NEW} so a per-device failure stays
     * isolated and the batch can continue.
     *
     * <p>Visibility: package-private so the integration test can assert
     * the refresh path is wired through {@link DiffCacheService} (which
     * applies the source-pair guard).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean refreshOneDevice(UUID tenantId, UUID deviceId, DiffType type) {
        return switch (type) {
            case SOFTWARE -> {
                SoftwareDiffSummary summary =
                        softwareDiffService.summarize(tenantId, deviceId);
                yield diffCacheService.upsertSoftwareDiffCache(tenantId, deviceId, summary);
            }
            case OUTDATED -> {
                OutdatedDiffSummary summary =
                        outdatedDiffService.summarize(tenantId, deviceId);
                yield diffCacheService.upsertOutdatedDiffCache(tenantId, deviceId, summary);
            }
        };
    }
}

package com.example.endpointadmin.service.diff;

import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * BE-024c v2-c-pre-2-C-B {@link DiffCacheBackfillWorker} — periodic sweep
 * that drives {@link DiffCacheBackfillService} for every tenant for both
 * {@link DiffType#SOFTWARE} and {@link DiffType#OUTDATED}.
 *
 * <p>The AFTER_COMMIT listener from v2-c-pre-2-C-A handles real-time
 * refresh on every ingest. The worker catches up the residual:
 * <ul>
 *   <li>devices ingested before v2-c-pre-2-C-A landed,</li>
 *   <li>devices whose listener invocation failed and the catch-log
 *       swallowed (cache row temporarily stale until next refresh),</li>
 *   <li>devices whose source-pair tuple is older than the latest
 *       state-history / outdated-snapshot row (idempotent re-summarize).</li>
 * </ul>
 *
 * <p>Source-pair guard from v2-c-pre-2-C-A keeps this idempotent: a cache
 * row already at the latest source tuple is left untouched and counts as
 * {@code skippedStale} (zero churn). Default cadence 10 min; configurable.
 */
@Component
public class DiffCacheBackfillWorker {

    private static final Logger log = LoggerFactory.getLogger(DiffCacheBackfillWorker.class);

    private final DiffCacheBackfillService backfillService;
    private final JdbcTemplate jdbc;
    private final int pageSize;

    public DiffCacheBackfillWorker(
            DiffCacheBackfillService backfillService,
            JdbcTemplate jdbc,
            @Value("${endpoint-admin.diff-cache.backfill-page-size:200}") int pageSize) {
        this.backfillService = backfillService;
        this.jdbc = jdbc;
        if (pageSize <= 0 || pageSize > 5000) {
            throw new IllegalArgumentException("backfill-page-size out of range [1, 5000]: " + pageSize);
        }
        this.pageSize = pageSize;
    }

    /**
     * Periodic sweep. Default 10-minute cadence with 10-minute initial
     * delay so application startup is not interfered with.
     */
    @Scheduled(
            fixedDelayString = "${endpoint-admin.diff-cache.backfill-interval-ms:600000}",
            initialDelayString = "${endpoint-admin.diff-cache.backfill-initial-delay-ms:600000}")
    public void sweep() {
        long start = System.nanoTime();
        List<UUID> tenants = listAllTenants();
        if (tenants.isEmpty()) {
            log.debug("DiffCache backfill: no tenants found");
            return;
        }
        DiffCacheBackfillResult softwareAcc = DiffCacheBackfillResult.empty();
        DiffCacheBackfillResult outdatedAcc = DiffCacheBackfillResult.empty();
        for (UUID tenantId : tenants) {
            try {
                softwareAcc = softwareAcc.plus(
                        backfillService.backfillTenant(tenantId, DiffType.SOFTWARE, pageSize));
                outdatedAcc = outdatedAcc.plus(
                        backfillService.backfillTenant(tenantId, DiffType.OUTDATED, pageSize));
            } catch (RuntimeException ex) {
                // Per-tenant boundary catch — a bad tenant cannot kill
                // the rest of the sweep. Per-device boundary catch lives
                // inside backfillBatch.
                log.warn("DiffCache backfill sweep failed tenant={} error={}: {}",
                        tenantId, ex.getClass().getSimpleName(), ex.getMessage());
            }
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        log.info("DiffCache backfill sweep complete tenants={} "
                        + "software_checked={} software_changed={} software_skippedStale={} software_errors={} "
                        + "outdated_checked={} outdated_changed={} outdated_skippedStale={} outdated_errors={} "
                        + "elapsed_ms={}",
                tenants.size(),
                softwareAcc.checked(), softwareAcc.changed(),
                softwareAcc.skippedStale(), softwareAcc.errors(),
                outdatedAcc.checked(), outdatedAcc.changed(),
                outdatedAcc.skippedStale(), outdatedAcc.errors(),
                elapsedMs);
    }

    private List<UUID> listAllTenants() {
        return jdbc.query(
                "SELECT DISTINCT tenant_id FROM endpoint_admin_service.endpoint_devices "
                + "ORDER BY tenant_id",
                (rs, i) -> (UUID) rs.getObject("tenant_id"));
    }
}

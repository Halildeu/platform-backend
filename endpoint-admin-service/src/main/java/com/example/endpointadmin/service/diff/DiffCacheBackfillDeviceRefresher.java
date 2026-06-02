package com.example.endpointadmin.service.diff;

import com.example.endpointadmin.service.EndpointOutdatedSoftwareDiffService;
import com.example.endpointadmin.service.EndpointSoftwareInventoryDiffService;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * BE-024c v2-c-pre-2-C-B {@link DiffCacheBackfillDeviceRefresher} —
 * extracted from {@link DiffCacheBackfillService} so the
 * {@link Propagation#REQUIRES_NEW} contract is enforced by the Spring
 * proxy (Codex 019e8a09 iter-1 must-fix #1: self-invocation bypasses
 * the transactional proxy when the @Transactional method is called from
 * within the same bean, so refreshOneDevice's REQUIRES_NEW guarantee
 * was a no-op in the original baseline impl).
 *
 * <p>This bean exists solely to provide the proxy boundary. The
 * batch/tenant orchestration stays in {@code DiffCacheBackfillService};
 * it injects this refresher and the proxied {@code refreshDevice} call
 * crosses the proxy each time → each per-device unit runs in its own
 * transaction independent of the orchestrator.
 */
@Service
public class DiffCacheBackfillDeviceRefresher {

    private final EndpointSoftwareInventoryDiffService softwareDiffService;
    private final EndpointOutdatedSoftwareDiffService outdatedDiffService;
    private final DiffCacheService diffCacheService;

    public DiffCacheBackfillDeviceRefresher(
            EndpointSoftwareInventoryDiffService softwareDiffService,
            EndpointOutdatedSoftwareDiffService outdatedDiffService,
            DiffCacheService diffCacheService) {
        this.softwareDiffService = softwareDiffService;
        this.outdatedDiffService = outdatedDiffService;
        this.diffCacheService = diffCacheService;
    }

    /**
     * Refresh exactly one device's cache row for one type. The
     * {@link Propagation#REQUIRES_NEW} suspends any caller's transaction
     * and opens a fresh one for the summarize + upsert pair so a
     * per-device failure cannot pollute the orchestrator's transaction
     * boundary.
     *
     * @return {@code true} if the cache row was actually mutated by the
     *         writer; {@code false} if the writer's UPSERT-WHERE returned
     *         no rows (identical-payload no-op, stale source-pair, or
     *         from-downgrade — caller cannot disambiguate from boolean
     *         alone).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean refreshDevice(UUID tenantId, UUID deviceId, DiffType type) {
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

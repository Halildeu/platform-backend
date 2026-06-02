package com.example.endpointadmin.service.diff;

/**
 * BE-024c v2-c-pre-2-C-B {@link DiffCacheBackfillService} per-call summary
 * (Codex 019e89e8 iter-5 plan AGREE on response shape from earlier
 * 019e89a3 iter-2 absorb: checked / changed / skippedStale).
 *
 * <p>{@code checked} counts every (tenant, device, type) refresh attempt.
 * {@code changed} = writer reported a cache row UPSERT actually mutated the
 * row (status / counts / source-tuple change). {@code skippedStale} =
 * writer's source-pair guard rejected the candidate because the cached
 * row's tuple was already &gt;= the candidate's.
 *
 * <p>{@code errors} counts individual per-device exceptions caught at the
 * batch boundary so a single bad device cannot abort the whole sweep —
 * each per-device call runs in its own {@code REQUIRES_NEW} via
 * {@link DiffCacheRefreshService}.
 */
public record DiffCacheBackfillResult(
        long checked,
        long changed,
        long skippedStale,
        long errors,
        long elapsedMs) {

    public static DiffCacheBackfillResult empty() {
        return new DiffCacheBackfillResult(0L, 0L, 0L, 0L, 0L);
    }

    /**
     * Combine two batch results — the worker uses this to fold per-tenant
     * results into a single sweep summary.
     */
    public DiffCacheBackfillResult plus(DiffCacheBackfillResult other) {
        return new DiffCacheBackfillResult(
                this.checked + other.checked,
                this.changed + other.changed,
                this.skippedStale + other.skippedStale,
                this.errors + other.errors,
                this.elapsedMs + other.elapsedMs);
    }
}

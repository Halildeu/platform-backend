package com.example.endpointadmin.service.diff;

/**
 * BE-024c v2-c-pre-2-C-B {@link DiffCacheBackfillService} per-call summary.
 *
 * <p>Codex 019e8a09 iter-1 must-fix #4 absorb: the writer-false signal
 * conflates three cases (identical-payload no-op + stale source-pair
 * reject + from-downgrade reject) which the caller cannot disambiguate
 * from boolean alone, so the field is named {@code unchanged} (not
 * {@code skippedStale}) to reflect the actual semantics. A future
 * UPSERT path that returns an enum could separate STALE_REJECTED from
 * UNCHANGED; until then the conservative name is the truthful one.
 *
 * <ul>
 *   <li>{@code checked} — every (tenant, device, type) refresh attempt.</li>
 *   <li>{@code changed} — writer's UPDATE branch actually mutated the
 *       cache row (status / counts / source-tuple change).</li>
 *   <li>{@code unchanged} — writer's UPSERT-WHERE returned no rows:
 *       identical-payload no-op OR stale source-pair guard reject OR
 *       from-downgrade guard reject. Caller cannot disambiguate from
 *       this counter alone.</li>
 *   <li>{@code errors} — per-device exceptions caught at the batch
 *       boundary so a single bad device cannot abort the whole sweep —
 *       each per-device call runs in its own {@code REQUIRES_NEW} via
 *       {@link DiffCacheBackfillDeviceRefresher}.</li>
 *   <li>{@code elapsedMs} — wall-clock for the batch / tenant sweep.</li>
 * </ul>
 */
public record DiffCacheBackfillResult(
        long checked,
        long changed,
        long unchanged,
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
                this.unchanged + other.unchanged,
                this.errors + other.errors,
                this.elapsedMs + other.elapsedMs);
    }
}

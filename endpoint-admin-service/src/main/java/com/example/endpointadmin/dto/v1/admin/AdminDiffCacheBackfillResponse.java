package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.service.diff.DiffCacheBackfillResult;
import com.example.endpointadmin.service.diff.DiffType;
import java.util.UUID;

/**
 * BE-024c v2-c-pre-2-C-B {@code POST /api/v1/admin/diff-cache/backfill}
 * response body. Shape matches Codex 019e89a3 iter-2 absorb on
 * checked/changed semantics + Codex 019e8a09 iter-1 must-fix #4 absorb
 * on honest {@code unchanged} naming:
 *
 * <ul>
 *   <li>{@code checked} — every refresh attempt the batch invoked.</li>
 *   <li>{@code changed} — rows the writer's UPDATE branch actually
 *       mutated.</li>
 *   <li>{@code unchanged} — writer returned false; conflates three
 *       cases: identical-payload no-op + stale source-pair guard reject
 *       + from-downgrade guard reject. Caller cannot disambiguate from
 *       this single counter (see {@link DiffCacheBackfillResult} for the
 *       authoritative docstring).</li>
 *   <li>{@code errors} — per-device exceptions caught at the batch
 *       boundary so a single bad device does not abort the sweep.</li>
 *   <li>{@code elapsedMs} — total wall-clock for the batch.</li>
 * </ul>
 *
 * @param scope {@code "TENANT"} if the request used tenant-scoped sweep,
 *              {@code "DEVICES"} if the request supplied an explicit
 *              {@code deviceIds} list.
 */
public record AdminDiffCacheBackfillResponse(
        UUID tenantId,
        DiffType type,
        String scope,
        long checked,
        long changed,
        long unchanged,
        long errors,
        long elapsedMs) {

    public static AdminDiffCacheBackfillResponse from(
            UUID tenantId, DiffType type, String scope, DiffCacheBackfillResult result) {
        return new AdminDiffCacheBackfillResponse(
                tenantId, type, scope,
                result.checked(), result.changed(),
                result.unchanged(), result.errors(),
                result.elapsedMs());
    }
}

package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.service.diff.DiffCacheBackfillResult;
import com.example.endpointadmin.service.diff.DiffType;
import java.util.UUID;

/**
 * BE-024c v2-c-pre-2-C-B {@code POST /api/v1/admin/diff-cache/backfill}
 * response body. Shape matches Codex 019e89a3 iter-2 absorb on
 * checked/changed semantics — {@code checked} is every refresh attempt,
 * {@code changed} is rows the writer actually mutated, {@code unchanged}
 * is the source-pair guard reject (zero-churn idempotency), {@code errors}
 * is per-device exceptions caught at the batch boundary.
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

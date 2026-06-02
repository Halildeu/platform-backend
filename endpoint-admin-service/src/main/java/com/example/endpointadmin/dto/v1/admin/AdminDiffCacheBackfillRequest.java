package com.example.endpointadmin.dto.v1.admin;

import com.example.endpointadmin.service.diff.DiffType;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;

/**
 * BE-024c v2-c-pre-2-C-B {@code POST /api/v1/admin/diff-cache/backfill}
 * request body. Tenant id is resolved from the request context via
 * {@code TenantContextResolver} so the body cannot override it (defence
 * in depth — Codex iter-2 absorb on admin scope semantics).
 *
 * <ul>
 *   <li>{@code type} — required: {@code SOFTWARE} | {@code OUTDATED}
 *       (mirror of {@link DiffType}; no {@code BOTH} convenience so a
 *       caller is forced to be explicit about the surface they are
 *       refreshing).</li>
 *   <li>{@code deviceIds} — optional list. When present, the backfill is
 *       scoped to just those devices (manual targeted catch-up). When
 *       absent, the whole tenant is swept via
 *       {@code DiffCacheBackfillService.backfillTenant}.</li>
 *   <li>{@code pageSize} — optional override on the tenant-scoped sweep
 *       page size. Bounded [1, 5000] to match
 *       {@code DiffCacheBackfillService}; default 200 matches the worker
 *       default so manual / scheduled behaviour stays consistent.</li>
 * </ul>
 */
public record AdminDiffCacheBackfillRequest(
        @NotNull DiffType type,
        List<UUID> deviceIds,
        Integer pageSize) {
}

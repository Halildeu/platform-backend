package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminDiffCacheBackfillRequest;
import com.example.endpointadmin.dto.v1.admin.AdminDiffCacheBackfillResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.diff.DiffCacheBackfillResult;
import com.example.endpointadmin.service.diff.DiffCacheBackfillService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * BE-024c v2-c-pre-2-C-B {@code POST /api/v1/admin/diff-cache/backfill}
 * admin endpoint. Triggers an immediate manual backfill of the diff cache
 * for the resolved tenant (Codex 019e89a3 iter-2 absorb: response shape
 * checked/changed/skippedStale + manager-level authz).
 *
 * <p>The scheduled {@code DiffCacheBackfillWorker} runs every 10 minutes
 * by default. This endpoint exists for operators who need to force an
 * immediate refresh after a known issue (e.g. a corrected source-pair
 * tuple, a regression rollback that left stale cache rows, or a tenant
 * onboarding catch-up).
 *
 * <p>Authz: {@link EndpointAdminAuthz#MANAGER} — the cache itself is
 * read-only from a tenant-data perspective (the source state-history /
 * outdated-snapshot rows are the canonical truth), but a manual sweep is
 * a write operation against {@code endpoint_software_diff_cache} +
 * {@code endpoint_outdated_software_diff_cache}, so manager-level fits.
 * Tenant boundary is enforced by
 * {@link TenantContextResolver#resolveRequired()} so the request body
 * cannot smuggle in another tenant id.
 */
@RestController
@RequestMapping("/api/v1/admin/diff-cache")
public class AdminDiffCacheController {

    private final DiffCacheBackfillService backfillService;
    private final TenantContextResolver tenantContextResolver;

    public AdminDiffCacheController(
            DiffCacheBackfillService backfillService,
            TenantContextResolver tenantContextResolver) {
        this.backfillService = backfillService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/backfill")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminDiffCacheBackfillResponse> backfill(
            @Valid @RequestBody AdminDiffCacheBackfillRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        UUID tenantId = context.tenantId();

        DiffCacheBackfillResult result;
        String scope;
        if (request.deviceIds() != null && !request.deviceIds().isEmpty()) {
            result = backfillService.backfillBatch(tenantId, request.type(), request.deviceIds());
            scope = "DEVICES";
        } else {
            int pageSize = request.pageSize() != null ? request.pageSize() : 200;
            result = backfillService.backfillTenant(tenantId, request.type(), pageSize);
            scope = "TENANT";
        }
        return ResponseEntity.ok(
                AdminDiffCacheBackfillResponse.from(tenantId, request.type(), scope, result));
    }
}

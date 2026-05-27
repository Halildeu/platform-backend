package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemRequest;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemResponse;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemSummary;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogRevokeRequest;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointSoftwareCatalogService;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BE-020 — Approved Software Catalog admin REST surface (Faz 22.5.3, PR-B).
 *
 * <p>Wires {@link EndpointSoftwareCatalogService} behind the same
 * {@code /api/v1/admin/...} convention as the other admin controllers
 * ({@link AdminEndpointCommandController}, {@code AdminEndpointDeviceController}
 * etc.). RBAC is enforced by the existing {@code module:endpoint-admin}
 * {@code can_view} / {@code can_manage} OpenFGA relations via
 * {@code @RequireModule} — no new scope opened (Codex 019e6a3e iter-2
 * acceptance #3).
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code GET    /api/v1/admin/endpoint-software-catalog} —
 *       paged list (optional {@code ?status=DRAFT|APPROVED|REVOKED} +
 *       {@code ?enabled=true|false} filters)</li>
 *   <li>{@code POST   /api/v1/admin/endpoint-software-catalog} —
 *       create (status=DRAFT, enabled=false)</li>
 *   <li>{@code GET    /api/v1/admin/endpoint-software-catalog/{catalogItemId}} —
 *       read single</li>
 *   <li>{@code PUT    /api/v1/admin/endpoint-software-catalog/{catalogItemId}} —
 *       update (DRAFT-only)</li>
 *   <li>{@code POST   /api/v1/admin/endpoint-software-catalog/{catalogItemId}/approve} —
 *       DRAFT → APPROVED + enabled=true (maker-checker invariant)</li>
 *   <li>{@code POST   /api/v1/admin/endpoint-software-catalog/{catalogItemId}/revoke} —
 *       APPROVED → REVOKED + revocation reason</li>
 * </ul>
 *
 * <p>Path key is the stable slug {@code catalogItemId}; the internal
 * {@code id UUID} stays internal (audit cross-ref only). No agent surface
 * here — the AG-027 install-time catalog query is the BE-021A preflight
 * contract, scoped out of BE-020.
 */
@RestController
@RequestMapping("/api/v1/admin/endpoint-software-catalog")
public class AdminEndpointSoftwareCatalogController {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 200;

    private final EndpointSoftwareCatalogService catalogService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointSoftwareCatalogController(
            EndpointSoftwareCatalogService catalogService,
            TenantContextResolver tenantContextResolver) {
        this.catalogService = catalogService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public Page<AdminCatalogItemSummary> listCatalogItems(
            @RequestParam(required = false) CatalogItemStatus status,
            @RequestParam(required = false) Boolean enabled,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        int resolvedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int resolvedPage = Math.max(0, page);
        Pageable pageable = PageRequest.of(resolvedPage, resolvedSize);
        return catalogService.listCatalogItems(context, status, enabled,
                pageable);
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminCatalogItemResponse createCatalogItem(
            @Valid @RequestBody AdminCatalogItemRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return catalogService.createCatalogItem(context, request);
    }

    @GetMapping("/{catalogItemId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public AdminCatalogItemResponse getCatalogItem(
            @PathVariable String catalogItemId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return catalogService.getCatalogItem(context, catalogItemId);
    }

    @PutMapping("/{catalogItemId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminCatalogItemResponse updateCatalogItem(
            @PathVariable String catalogItemId,
            @Valid @RequestBody AdminCatalogItemRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return catalogService.updateCatalogItem(context, catalogItemId, request);
    }

    @PostMapping("/{catalogItemId}/approve")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminCatalogItemResponse approveCatalogItem(
            @PathVariable String catalogItemId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return catalogService.approveCatalogItem(context, catalogItemId);
    }

    @PostMapping("/{catalogItemId}/revoke")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminCatalogItemResponse revokeCatalogItem(
            @PathVariable String catalogItemId,
            @Valid @RequestBody AdminCatalogRevokeRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return catalogService.revokeCatalogItem(context, catalogItemId, request);
    }
}

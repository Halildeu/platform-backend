package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestRejection;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogUninstallSettingsChangeRequestResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.CatalogUninstallSettingsChangeRequestService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * AG-028 Phase 0 — REST surface for the catalog uninstall settings
 * change-request maker-checker flow (Faz 22.5.6).
 *
 * <p>RBAC is enforced by the existing {@code module:endpoint-admin}
 * relations via {@code @RequireModule}. No new scope opened. Service
 * layer enforces the additional invariants (maker-checker, elevated
 * approver for unprotect, partial unique one-open-request index,
 * catalog APPROVED state guard).
 *
 * <p>Routes:
 * <ul>
 *   <li>{@code POST /api/v1/admin/catalog-items/{id}/uninstall-settings-change}
 *       — propose a flag flip (caller becomes proposer).</li>
 *   <li>{@code POST .../uninstall-settings-change/{rid}/approve}
 *       — approve + apply (caller must differ from proposer).</li>
 *   <li>{@code POST .../uninstall-settings-change/{rid}/reject}
 *       — reject with reason.</li>
 *   <li>{@code GET  .../uninstall-settings-change}
 *       — list propose/approve history for the catalog item.</li>
 *   <li>{@code GET  .../uninstall-settings-change/{rid}}
 *       — fetch a specific request.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/admin/catalog-items/{catalogItemId}/uninstall-settings-change")
public class AdminCatalogUninstallSettingsController {

    private final CatalogUninstallSettingsChangeRequestService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminCatalogUninstallSettingsController(
            CatalogUninstallSettingsChangeRequestService service,
            TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminCatalogUninstallSettingsChangeRequestResponse> propose(
            @PathVariable UUID catalogItemId,
            @Valid @RequestBody AdminCatalogUninstallSettingsChangeRequestCreate request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        AdminCatalogUninstallSettingsChangeRequestResponse response =
                service.propose(context, catalogItemId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{requestId}/approve")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminCatalogUninstallSettingsChangeRequestResponse approve(
            @PathVariable UUID catalogItemId,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false)
                    AdminCatalogUninstallSettingsChangeRequestApproval body) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.approve(context, catalogItemId, requestId, body);
    }

    @PostMapping("/{requestId}/reject")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.MANAGER)
    public AdminCatalogUninstallSettingsChangeRequestResponse reject(
            @PathVariable UUID catalogItemId,
            @PathVariable UUID requestId,
            @Valid @RequestBody AdminCatalogUninstallSettingsChangeRequestRejection body) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.reject(context, catalogItemId, requestId, body);
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public List<AdminCatalogUninstallSettingsChangeRequestResponse> list(
            @PathVariable UUID catalogItemId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.listForCatalogItem(context, catalogItemId);
    }

    @GetMapping("/{requestId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE,
            relation = EndpointAdminAuthz.VIEWER)
    public AdminCatalogUninstallSettingsChangeRequestResponse get(
            @PathVariable UUID catalogItemId,
            @PathVariable UUID requestId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.get(context, catalogItemId, requestId);
    }
}

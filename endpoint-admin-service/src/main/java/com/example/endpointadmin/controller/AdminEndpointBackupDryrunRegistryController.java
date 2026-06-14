package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootCreateRequest;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootResponse;
import com.example.endpointadmin.dto.v1.admin.AdminManagedRootSetEnabledRequest;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointBackupDryrunRegistryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Faz 22.8A.3a (#648) — managed-data-root registry REST surface for the backup
 * dry-run feature (contract §4; Codex 019ec45e "registry-first").
 *
 * <pre>
 * POST   /api/v1/admin/backup-dryrun/managed-roots            register a root
 * GET    /api/v1/admin/backup-dryrun/managed-roots            list (path-free)
 * PATCH  /api/v1/admin/backup-dryrun/managed-roots/{id}       enable/disable
 * </pre>
 *
 * <p>Double-gated + fail-closed in the service (feature flag 503 + per-tenant
 * opt-in 403). Responses are PATH-FREE (the raw {@code localPath} never leaves
 * the backend). The dedicated issuing surface (22.8A.3b) references these roots
 * by opaque {@code rootRef} only.
 *
 * <p>RBAC: {@code module:endpoint-admin can_manage} for writes,
 * {@code can_view} for reads.
 */
@RestController
@RequestMapping("/api/v1/admin/backup-dryrun/managed-roots")
public class AdminEndpointBackupDryrunRegistryController {

    private final EndpointBackupDryrunRegistryService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointBackupDryrunRegistryController(
            EndpointBackupDryrunRegistryService service,
            TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminManagedRootResponse> register(
            @Valid @RequestBody AdminManagedRootCreateRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        AdminManagedRootResponse response = service.register(context, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ResponseEntity<List<AdminManagedRootResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return ResponseEntity.ok(service.list(context, page, size));
    }

    @PatchMapping("/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminManagedRootResponse> setEnabled(
            @PathVariable UUID id,
            @Valid @RequestBody AdminManagedRootSetEnabledRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return ResponseEntity.ok(service.setEnabled(context, id, request));
    }
}

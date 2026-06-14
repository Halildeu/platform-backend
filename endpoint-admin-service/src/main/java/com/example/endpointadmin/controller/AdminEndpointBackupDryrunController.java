package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestApproval;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestCreate;
import com.example.endpointadmin.dto.v1.admin.AdminBackupDryrunRequestResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointBackupDryrunService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Faz 22.8A.3b (#648) — dedicated backup dry-run ISSUING REST surface
 * (propose→approve dual-control). The generic /commands surface rejects
 * COLLECT_BACKUP_DRYRUN with 422 (DEDICATED_PATH_ONLY); this is the only legal
 * issuing path.
 *
 * <pre>
 * POST   /api/v1/admin/endpoint-devices/{deviceId}/backup-dryruns
 * POST   /api/v1/admin/endpoint-devices/{deviceId}/backup-dryruns/{requestId}/approve
 * GET    /api/v1/admin/endpoint-devices/{deviceId}/backup-dryruns/{requestId}
 * GET    /api/v1/admin/endpoint-devices/{deviceId}/backup-dryruns
 * </pre>
 *
 * <p>HTTP: 201 propose (PENDING_APPROVAL) · 200 approve (APPROVED + commandId) ·
 * 403 maker-checker · 409 in-flight / state-not-pending / registry-drift ·
 * 422 unknown/disabled/BYOD root / capability-missing · 424 stale heartbeat ·
 * 503 feature disabled. RBAC: can_manage writes, can_view reads.
 */
@RestController
@RequestMapping("/api/v1/admin/endpoint-devices/{deviceId}/backup-dryruns")
public class AdminEndpointBackupDryrunController {

    private final EndpointBackupDryrunService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointBackupDryrunController(EndpointBackupDryrunService service,
                                               TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminBackupDryrunRequestResponse> propose(
            @PathVariable UUID deviceId,
            @Valid @RequestBody AdminBackupDryrunRequestCreate request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return ResponseEntity.status(HttpStatus.CREATED).body(service.propose(context, deviceId, request));
    }

    @PostMapping("/{requestId}/approve")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<AdminBackupDryrunRequestResponse> approve(
            @PathVariable UUID deviceId,
            @PathVariable UUID requestId,
            @Valid @RequestBody(required = false) AdminBackupDryrunRequestApproval body) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return ResponseEntity.ok(service.approve(context, deviceId, requestId, body));
    }

    @GetMapping("/{requestId}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ResponseEntity<AdminBackupDryrunRequestResponse> get(
            @PathVariable UUID deviceId,
            @PathVariable UUID requestId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return ResponseEntity.ok(service.get(context, deviceId, requestId));
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public ResponseEntity<List<AdminBackupDryrunRequestResponse>> list(
            @PathVariable UUID deviceId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return ResponseEntity.ok(service.listForDevice(context, deviceId, page, size));
    }
}

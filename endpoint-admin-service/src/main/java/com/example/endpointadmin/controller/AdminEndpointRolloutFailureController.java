package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.CreateRolloutFailureRequest;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEventResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointRolloutFailureService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Faz 22.5 #527 slice-1a — read + manual-seed REST surface for the rollout
 * failed-device queue.
 *
 * <pre>
 * POST /api/v1/admin/rollout-failures                 — manual operator seed (initial `new`)
 * GET  /api/v1/admin/rollout-failures?rolloutId&waveId — list a wave's failures
 * GET  /api/v1/admin/rollout-failures/{id}            — one aggregate
 * GET  /api/v1/admin/rollout-failures/{id}/events     — append-only ledger history
 * </pre>
 *
 * <p>DEFERRED (contract §9, NOT in this slice): transition endpoints, auto
 * ingest/classifier, stop-line threshold compute, GitHub escalation generation,
 * wave export. The enforcement flags stay false. RBAC mirrors the rest of the
 * surface: {@code can_manage} for the seed write, {@code can_view} for reads.
 */
@RestController
@RequestMapping("/api/v1/admin/rollout-failures")
public class AdminEndpointRolloutFailureController {

    private final EndpointRolloutFailureService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointRolloutFailureController(EndpointRolloutFailureService service,
                                                 TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public RolloutFailureResponse create(@Valid @RequestBody CreateRolloutFailureRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.createManual(context, request);
    }

    @GetMapping
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public List<RolloutFailureResponse> list(@RequestParam String rolloutId, @RequestParam String waveId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.listByWave(context, rolloutId, waveId);
    }

    @GetMapping("/{id}")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public RolloutFailureResponse get(@PathVariable UUID id) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.get(context, id);
    }

    @GetMapping("/{id}/events")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    public List<RolloutFailureEventResponse> events(@PathVariable UUID id) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.listEvents(context, id);
    }
}

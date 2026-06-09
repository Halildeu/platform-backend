package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.CreateWaveMetricsSnapshotRequest;
import com.example.endpointadmin.dto.v1.admin.WaveMetricsSnapshotResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutWaveMetricsSnapshotService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write surface for orchestrator wave/fleet metrics snapshots (Faz 22.5 #527
 * §9.3). The authenticated deployment orchestrator POSTs a rollout-scoped metrics
 * snapshot; the stop-line evaluator reads the latest one to compute §6's advisory
 * status. {@code can_manage}-gated (a privileged producer, like the manual seed).
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
public class AdminRolloutWaveMetricsController {

    private final RolloutWaveMetricsSnapshotService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminRolloutWaveMetricsController(RolloutWaveMetricsSnapshotService service,
                                             TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/endpoint-rollouts/{rolloutId}/waves/{waveId}/metrics-snapshot")
    @ResponseStatus(HttpStatus.CREATED)
    public WaveMetricsSnapshotResponse record(@PathVariable String rolloutId,
                                              @PathVariable String waveId,
                                              @Valid @RequestBody CreateWaveMetricsSnapshotRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.record(context.tenantId(), rolloutId, waveId, request);
    }
}

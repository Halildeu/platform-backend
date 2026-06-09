package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.CreateRolloutFailureRequest;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureSeedResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueSeedService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Write surface for the rollout failed-device queue (Faz 22.5 #527 slice-1b),
 * complementing #528's read-only {@code AdminRolloutFailureController}. A single
 * manual operator seed endpoint, {@code can_manage}-gated (the reads are
 * {@code can_view}). 201 on create; 400 on evidence validation failure; 409 when
 * an active failure already exists for the device in the wave.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
public class AdminRolloutFailureSeedController {

    private final RolloutFailureQueueSeedService seedService;
    private final TenantContextResolver tenantContextResolver;

    public AdminRolloutFailureSeedController(RolloutFailureQueueSeedService seedService,
                                             TenantContextResolver tenantContextResolver) {
        this.seedService = seedService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/endpoint-rollout-failures")
    @ResponseStatus(HttpStatus.CREATED)
    public RolloutFailureSeedResponse seed(@Valid @RequestBody CreateRolloutFailureRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return seedService.seedManual(context.tenantId(), context.subject(), request);
    }
}

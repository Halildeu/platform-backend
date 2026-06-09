package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEscalationGenerator;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Generates (previews) the GitHub escalation issue body for a queue item (Faz
 * 22.5 #527 §9.4). Read-only ({@code can_view}); the live issue creation is an
 * operator-configured GitHub integration, not performed here.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
public class AdminRolloutFailureEscalationController {

    private final RolloutFailureQueueReadService readService;
    private final RolloutFailureEscalationGenerator generator;
    private final TenantContextResolver tenantContextResolver;

    public AdminRolloutFailureEscalationController(RolloutFailureQueueReadService readService,
                                                  RolloutFailureEscalationGenerator generator,
                                                  TenantContextResolver tenantContextResolver) {
        this.readService = readService;
        this.generator = generator;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-rollout-failures/{failureId}/escalation-issue")
    public RolloutFailureEscalationResponse escalationIssue(@PathVariable("failureId") UUID failureId) {
        UUID tenantId = tenantContextResolver.resolveRequired().tenantId();
        return readService.getDetail(tenantId, failureId)
                .map(detail -> generator.generate(detail.item()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rollout failure not found"));
    }
}

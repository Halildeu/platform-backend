package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationPublishResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEscalationGenerator;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEscalationPublishService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Generates/previews the GitHub escalation issue body for a queue item (Faz
 * 22.5 #527 §9.4) and, when explicitly enabled/configured, publishes that
 * projection to GitHub. The GET preview remains read-only; POST is
 * can_manage-gated because it mutates both GitHub and the canonical queue item.
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
public class AdminRolloutFailureEscalationController {

    private final RolloutFailureQueueReadService readService;
    private final RolloutFailureEscalationGenerator generator;
    private final RolloutFailureEscalationPublishService publishService;
    private final TenantContextResolver tenantContextResolver;

    public AdminRolloutFailureEscalationController(RolloutFailureQueueReadService readService,
                                                  RolloutFailureEscalationGenerator generator,
                                                  RolloutFailureEscalationPublishService publishService,
                                                  TenantContextResolver tenantContextResolver) {
        this.readService = readService;
        this.generator = generator;
        this.publishService = publishService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-rollout-failures/{failureId}/escalation-issue")
    public RolloutFailureEscalationResponse escalationIssue(@PathVariable("failureId") UUID failureId) {
        UUID tenantId = tenantContextResolver.resolveRequired().tenantId();
        return readService.getDetail(tenantId, failureId)
                .map(detail -> generator.generate(detail.item()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "rollout failure not found"));
    }

    @PostMapping("/endpoint-rollout-failures/{failureId}/escalation-issue")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public ResponseEntity<RolloutFailureEscalationPublishResponse> publishEscalationIssue(
            @PathVariable("failureId") UUID failureId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        RolloutFailureEscalationPublishResponse response =
                publishService.publish(context.tenantId(), context.subject(), failureId);
        HttpStatus status = response.alreadyPublished() ? HttpStatus.OK : HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.PRAGMA, "no-cache")
                .body(response);
    }
}

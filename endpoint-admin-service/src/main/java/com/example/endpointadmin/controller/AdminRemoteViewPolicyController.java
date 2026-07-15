package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.PolicyChangeApprovalDto;
import com.example.endpointadmin.dto.v1.admin.RemoteViewPolicyPublicationDto;
import com.example.endpointadmin.dto.v1.admin.RemoteViewPolicyRevocationDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RemoteViewPolicyWorkflowService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Tenant-scoped, four-eyes remote-view policy proposal and publication. */
@RestController
@RequestMapping("/api/v1/admin/remote-view-policies")
@ConditionalOnProperty(prefix = "remote-view-policy", name = "enabled", havingValue = "true")
public class AdminRemoteViewPolicyController {
    private final RemoteViewPolicyWorkflowService service;
    private final TenantContextResolver tenantContextResolver;

    public AdminRemoteViewPolicyController(RemoteViewPolicyWorkflowService service,
                                           TenantContextResolver tenantContextResolver) {
        this.service = service;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping(value = "/proposals", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto propose(@RequestBody String rawRequest) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.propose(context, rawRequest);
    }

    @PostMapping(value = "/publications", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public RemoteViewPolicyPublicationDto publish(@RequestBody String rawRequest) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.publish(context, rawRequest);
    }

    @PostMapping(value = "/revocation-proposals", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public PolicyChangeApprovalDto proposeRevocation(@RequestBody String rawRequest) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.proposeRevocation(context, rawRequest);
    }

    @PostMapping(value = "/revocations", consumes = "application/json")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public RemoteViewPolicyRevocationDto revoke(@RequestBody String rawRequest) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return service.revoke(context, rawRequest);
    }
}

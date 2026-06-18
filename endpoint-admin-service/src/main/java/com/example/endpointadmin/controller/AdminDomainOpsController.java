package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.CreateDomainOpsRequest;
import com.example.endpointadmin.dto.v1.admin.DomainOpsRequestResponse;
import com.example.endpointadmin.dto.v1.admin.SubmitDomainOpsResultRequest;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.DomainOpsBrokerService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin")
public class AdminDomainOpsController {

    private final DomainOpsBrokerService domainOpsBrokerService;
    private final TenantContextResolver tenantContextResolver;

    public AdminDomainOpsController(DomainOpsBrokerService domainOpsBrokerService,
                                    TenantContextResolver tenantContextResolver) {
        this.domainOpsBrokerService = domainOpsBrokerService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/endpoint-devices/{deviceId}/domain-ops")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public DomainOpsRequestResponse create(@PathVariable UUID deviceId,
                                           @Valid @RequestBody CreateDomainOpsRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return DomainOpsRequestResponse.from(
                domainOpsBrokerService.create(context, deviceId, request));
    }

    @PostMapping("/endpoint-devices/{deviceId}/domain-ops/{operationId}/result")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public DomainOpsRequestResponse submitResult(@PathVariable UUID deviceId,
                                                 @PathVariable UUID operationId,
                                                 @Valid @RequestBody SubmitDomainOpsResultRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return DomainOpsRequestResponse.from(
                domainOpsBrokerService.submitResult(context, deviceId, operationId, request));
    }
}

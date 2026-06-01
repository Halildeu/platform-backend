package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.AdminStartupExposureSnapshotResponse;
import com.example.endpointadmin.model.EndpointStartupExposureSnapshot;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointStartupExposureService;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * BE — startup-exposure admin REST surface (Faz 22.5, AG-040-be).
 * Mirrors AG-039-be {@code AdminEndpointServicesController}.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointStartupExposureController {

    private final EndpointStartupExposureService startupExposureService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointStartupExposureController(
            EndpointStartupExposureService startupExposureService,
            TenantContextResolver tenantContextResolver) {
        this.startupExposureService = startupExposureService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @GetMapping("/endpoint-devices/{deviceId}/startup-exposure/latest")
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.VIEWER)
    @Transactional(readOnly = true)
    public AdminStartupExposureSnapshotResponse getLatest(@PathVariable UUID deviceId) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        EndpointStartupExposureSnapshot snapshot = startupExposureService
                .findLatest(context.tenantId(), deviceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND,
                        "No startup-exposure snapshot for device " + deviceId));
        return AdminStartupExposureSnapshotResponse.from(snapshot);
    }
}

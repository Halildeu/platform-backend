package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.RequireModule;
import com.example.endpointadmin.dto.v1.admin.CreateInstallRequest;
import com.example.endpointadmin.dto.v1.admin.EndpointCommandDto;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.EndpointAdminAuthz;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointAdminCommandService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * BE-021 — dedicated INSTALL_SOFTWARE creation surface (Faz 22.5).
 *
 * <pre>
 * POST /api/v1/admin/endpoint-devices/{deviceId}/installs
 * </pre>
 *
 * <p>The dedicated path is the only legal way to create an
 * INSTALL_SOFTWARE command — the generic
 * {@link AdminEndpointCommandController#createCommand(UUID, com.example.endpointadmin.dto.v1.admin.CreateEndpointCommandRequest)}
 * rejects the type with 409 (Codex 019e6dfb iter-3 P0-1 absorb). The
 * service recomputes the install preflight at command-creation time
 * (cached PASS reuse is forbidden), so a stale preflight cannot leak
 * past this gate.
 *
 * <p>HTTP semantics:
 * <ul>
 *   <li>{@code 201 Created} — PASS / WARN, command queued.</li>
 *   <li>{@code 409 Conflict} — preflight recompute returned BLOCK
 *       (body is the canonical {@code InstallPreflightResponse}), or
 *       an idempotency key was reused with mismatching device /
 *       catalog parameters.</li>
 *   <li>{@code 400} — request validation failure (missing
 *       catalogItemId, length > 128, etc).</li>
 *   <li>{@code 404} — device or catalog item not visible to the
 *       caller's tenant.</li>
 * </ul>
 *
 * <p>RBAC: {@code module:endpoint-admin can_manage}. The viewer
 * relation can call the GET install-preflight + GET installs surface
 * (BE-021A + BE-021); only the manager can issue the install command.
 */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminEndpointInstallController {

    private final EndpointAdminCommandService commandService;
    private final TenantContextResolver tenantContextResolver;

    public AdminEndpointInstallController(EndpointAdminCommandService commandService,
                                          TenantContextResolver tenantContextResolver) {
        this.commandService = commandService;
        this.tenantContextResolver = tenantContextResolver;
    }

    @PostMapping("/endpoint-devices/{deviceId}/installs")
    @ResponseStatus(HttpStatus.CREATED)
    @RequireModule(value = EndpointAdminAuthz.MODULE, relation = EndpointAdminAuthz.MANAGER)
    public EndpointCommandDto createInstall(@PathVariable UUID deviceId,
                                            @Valid @RequestBody CreateInstallRequest request) {
        AdminTenantContext context = tenantContextResolver.resolveRequired();
        return commandService.createInstall(context, deviceId, request);
    }
}

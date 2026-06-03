package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.CatalogUninstallSettingsChangeRequestService;
import com.example.endpointadmin.service.CatalogUninstallSettingsElevatedApproverRequiredException;
import com.example.endpointadmin.service.CatalogUninstallSettingsMakerCheckerViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AG-028 Phase 0 — MockMvc slice test for
 * {@link AdminCatalogUninstallSettingsController}.
 *
 * <p>Focus is on Codex post-impl iter-2 absorb: confirm that the two
 * custom service exceptions ({@link CatalogUninstallSettingsMakerCheckerViolationException}
 * and {@link CatalogUninstallSettingsElevatedApproverRequiredException})
 * actually surface as HTTP 403 — not HTTP 500. Without the
 * {@code extends ResponseStatusException} subclass change, the repo's
 * {@code GlobalExceptionHandler.@ExceptionHandler(Exception.class)}
 * catch-all would map them to {@code INTERNAL_SERVER_ERROR}.
 *
 * <p>Local security profile bypasses OpenFGA; RBAC happy paths are covered
 * by the existing {@code EndpointAdminAuthorizationAnnotationTest} reflection
 * coverage. This class is a wire-shape regression for the 403 status code.
 */
@WebMvcTest(AdminCatalogUninstallSettingsController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminCatalogUninstallSettingsControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final String SUBJECT = "alice@example.com";
    private static final UUID CATALOG_ID = UUID.randomUUID();
    private static final UUID REQUEST_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogUninstallSettingsChangeRequestService service;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void approveMakerCheckerViolationReturns403_notInternalError() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
        when(service.approve(any(), eq(CATALOG_ID), eq(REQUEST_ID), any()))
                .thenThrow(new CatalogUninstallSettingsMakerCheckerViolationException(
                        REQUEST_ID, "alice@example.com", "alice@example.com"));

        mockMvc.perform(post(
                        "/api/v1/admin/catalog-items/{cid}/uninstall-settings-change/{rid}/approve",
                        CATALOG_ID, REQUEST_ID))
                .andExpect(status().isForbidden());
    }

    @Test
    void approveElevatedApproverRequiredReturns403_notInternalError() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT));
        when(service.approve(any(), eq(CATALOG_ID), eq(REQUEST_ID), any()))
                .thenThrow(new CatalogUninstallSettingsElevatedApproverRequiredException(
                        REQUEST_ID, "bob@example.com"));

        mockMvc.perform(post(
                        "/api/v1/admin/catalog-items/{cid}/uninstall-settings-change/{rid}/approve",
                        CATALOG_ID, REQUEST_ID))
                .andExpect(status().isForbidden());
    }
}

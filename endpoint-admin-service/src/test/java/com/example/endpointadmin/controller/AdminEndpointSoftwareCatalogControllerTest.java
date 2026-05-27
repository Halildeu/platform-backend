package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemResponse;
import com.example.endpointadmin.dto.v1.admin.AdminCatalogItemSummary;
import com.example.endpointadmin.exception.CatalogMakerCheckerViolationException;
import com.example.endpointadmin.model.CatalogInstallerType;
import com.example.endpointadmin.model.CatalogItemStatus;
import com.example.endpointadmin.model.CatalogProvider;
import com.example.endpointadmin.model.CatalogRiskTier;
import com.example.endpointadmin.model.CatalogSilentArgsPolicy;
import com.example.endpointadmin.model.CatalogSourceTrust;
import com.example.endpointadmin.model.CatalogSourceType;
import com.example.endpointadmin.model.CatalogVersionPolicyType;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointSoftwareCatalogService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BE-020 PR-B (Faz 22.5.3) — MockMvc slice test for
 * {@link AdminEndpointSoftwareCatalogController}.
 *
 * <p>Local security profile bypasses OpenFGA; 401 / 403 paths are covered
 * by the existing {@code AdminEndpointAuthorizationSecurityTest} +
 * {@link EndpointAdminAuthorizationAnnotationTest} (reflection coverage
 * for {@code @RequireModule} annotations on every catalog route — Codex
 * 019e6a3e iter-2 acceptance #3). This class focuses on the wire-shape
 * + status-code contract: 200 happy path, 400 validation, 409 state
 * transition conflicts, 422 maker-checker violation.
 */
@WebMvcTest(AdminEndpointSoftwareCatalogController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointSoftwareCatalogControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ITEM_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final String SLUG = "7zip-7zip-winget-stable";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private EndpointSoftwareCatalogService catalogService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void listCatalogItemsReturns200WithSummaries() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.listCatalogItems(eq(context), eq((CatalogItemStatus) null), eq((Boolean) null), any()))
                .thenReturn(new PageImpl<>(List.of(summary())));

        mockMvc.perform(get("/api/v1/admin/endpoint-software-catalog"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].catalogItemId").value(SLUG))
                .andExpect(jsonPath("$.content[0].status").value("DRAFT"))
                .andExpect(jsonPath("$.content[0].provider").value("WINGET"))
                .andExpect(jsonPath("$.content[0].enabled").value(false));
    }

    @Test
    void listCatalogItemsAppliesStatusAndEnabledFilters() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.listCatalogItems(
                eq(context),
                eq(CatalogItemStatus.APPROVED),
                eq(Boolean.TRUE),
                any()))
                .thenReturn(new PageImpl<>(List.of()));

        mockMvc.perform(get("/api/v1/admin/endpoint-software-catalog")
                        .param("status", "APPROVED")
                        .param("enabled", "true"))
                .andExpect(status().isOk());

        verify(catalogService).listCatalogItems(
                eq(context), eq(CatalogItemStatus.APPROVED), eq(Boolean.TRUE), any());
    }

    @Test
    void getCatalogItemReturns200() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.getCatalogItem(context, SLUG)).thenReturn(draftResponse());

        mockMvc.perform(get("/api/v1/admin/endpoint-software-catalog/{slug}", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogItemId").value(SLUG))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.provider").value("WINGET"))
                .andExpect(jsonPath("$.publisher").value("Igor Pavlov"));
    }

    @Test
    void getCatalogItemReturns404WhenServiceThrowsNotFound() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.getCatalogItem(context, SLUG))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "Catalog item not found."));

        mockMvc.perform(get("/api/v1/admin/endpoint-software-catalog/{slug}", SLUG))
                .andExpect(status().isNotFound());
    }

    @Test
    void createCatalogItemReturns200() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.createCatalogItem(eq(context), any())).thenReturn(draftResponse());

        mockMvc.perform(post("/api/v1/admin/endpoint-software-catalog")
                        .contentType("application/json")
                        .content(validRequestJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.catalogItemId").value(SLUG))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.enabled").value(false));

        verify(catalogService).createCatalogItem(eq(context), any());
    }

    @Test
    void createCatalogItemReturns400OnMissingFields() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);

        mockMvc.perform(post("/api/v1/admin/endpoint-software-catalog")
                        .contentType("application/json")
                        .content("""
                                {
                                  "catalogItemId": "7zip-incomplete",
                                  "provider": "WINGET"
                                }
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createCatalogItemReturns409OnDuplicateSlug() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.createCatalogItem(eq(context), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "Catalog item with this id already exists for the tenant."));

        mockMvc.perform(post("/api/v1/admin/endpoint-software-catalog")
                        .contentType("application/json")
                        .content(validRequestJson()))
                .andExpect(status().isConflict());
    }

    @Test
    void updateCatalogItemReturns200() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.updateCatalogItem(eq(context), eq(SLUG), any()))
                .thenReturn(draftResponse());

        mockMvc.perform(put("/api/v1/admin/endpoint-software-catalog/{slug}", SLUG)
                        .contentType("application/json")
                        .content(validRequestJson()))
                .andExpect(status().isOk());

        verify(catalogService).updateCatalogItem(eq(context), eq(SLUG), any());
    }

    @Test
    void updateCatalogItemReturns409OnNonDraftState() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.updateCatalogItem(eq(context), eq(SLUG), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
                        "Catalog item is not in DRAFT and cannot be edited."));

        mockMvc.perform(put("/api/v1/admin/endpoint-software-catalog/{slug}", SLUG)
                        .contentType("application/json")
                        .content(validRequestJson()))
                .andExpect(status().isConflict());
    }

    @Test
    void approveCatalogItemReturns200() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.approveCatalogItem(context, SLUG))
                .thenReturn(approvedResponse());

        mockMvc.perform(post("/api/v1/admin/endpoint-software-catalog/{slug}/approve", SLUG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.approvedBySubject").value("bob@example.com"));
    }

    @Test
    void approveCatalogItemReturns422OnSelfApproval() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.approveCatalogItem(context, SLUG))
                .thenThrow(new CatalogMakerCheckerViolationException());

        mockMvc.perform(post("/api/v1/admin/endpoint-software-catalog/{slug}/approve", SLUG))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void revokeCatalogItemReturns200() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(catalogService.revokeCatalogItem(eq(context), eq(SLUG), any()))
                .thenReturn(revokedResponse());

        mockMvc.perform(post("/api/v1/admin/endpoint-software-catalog/{slug}/revoke", SLUG)
                        .contentType("application/json")
                        .content("""
                                {
                                  "revocationReason": "vendor pulled the package"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.revocationReason").value("vendor pulled the package"));
    }

    @Test
    void revokeCatalogItemReturns400WhenReasonMissing() throws Exception {
        AdminTenantContext context = adminContext();
        when(tenantContextResolver.resolveRequired()).thenReturn(context);

        mockMvc.perform(post("/api/v1/admin/endpoint-software-catalog/{slug}/revoke", SLUG)
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    // ----------------------------------------------------------------
    // Helpers

    private AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin@example.com");
    }

    private String validRequestJson() {
        return """
                {
                  "catalogItemId": "%s",
                  "provider": "WINGET",
                  "sourceType": "WINGET",
                  "sourceName": "winget",
                  "sourceTrust": "WINGET_COMMUNITY_REVIEWED",
                  "packageId": "7zip.7zip",
                  "displayName": "7-Zip",
                  "publisher": "Igor Pavlov",
                  "versionPolicyType": "LATEST",
                  "installerType": "WINGET_SILENT",
                  "silentArgsPolicy": "DEFAULT",
                  "detectionRule": {
                    "type": "WINGET_PACKAGE",
                    "wingetPackageId": "7zip.7zip"
                  },
                  "riskTier": "LOW"
                }
                """.formatted(SLUG);
    }

    private AdminCatalogItemSummary summary() {
        return new AdminCatalogItemSummary(
                ITEM_ID,
                SLUG,
                CatalogItemStatus.DRAFT,
                CatalogProvider.WINGET,
                "7zip.7zip",
                "7-Zip",
                "Igor Pavlov",
                CatalogRiskTier.LOW,
                false,
                Instant.parse("2026-05-27T10:00:00Z")
        );
    }

    private AdminCatalogItemResponse draftResponse() {
        return baseResponse(CatalogItemStatus.DRAFT, false, null, null, null, null, null);
    }

    private AdminCatalogItemResponse approvedResponse() {
        return baseResponse(
                CatalogItemStatus.APPROVED, true,
                "bob@example.com", Instant.parse("2026-05-27T11:00:00Z"),
                null, null, null);
    }

    private AdminCatalogItemResponse revokedResponse() {
        return baseResponse(
                CatalogItemStatus.REVOKED, false,
                "bob@example.com", Instant.parse("2026-05-27T11:00:00Z"),
                "bob@example.com", Instant.parse("2026-05-27T12:00:00Z"),
                "vendor pulled the package");
    }

    private AdminCatalogItemResponse baseResponse(
            CatalogItemStatus status,
            boolean enabled,
            String approvedBy,
            Instant approvedAt,
            String revokedBy,
            Instant revokedAt,
            String revocationReason) {
        Instant now = Instant.parse("2026-05-27T10:00:00Z");
        return new AdminCatalogItemResponse(
                ITEM_ID,
                TENANT_ID,
                SLUG,
                status,
                CatalogProvider.WINGET,
                CatalogSourceType.WINGET,
                "winget",
                CatalogSourceTrust.WINGET_COMMUNITY_REVIEWED,
                "7zip.7zip",
                "7-Zip",
                "Igor Pavlov",
                CatalogVersionPolicyType.LATEST,
                null,
                CatalogInstallerType.WINGET_SILENT,
                CatalogSilentArgsPolicy.DEFAULT,
                null,
                null,
                Map.of("type", "WINGET_PACKAGE", "wingetPackageId", "7zip.7zip"),
                CatalogRiskTier.LOW,
                enabled,
                "alice@example.com",
                now,
                approvedBy != null ? approvedBy : "alice@example.com",
                now,
                approvedBy,
                approvedAt,
                revokedBy,
                revokedAt,
                revocationReason
        );
    }
}

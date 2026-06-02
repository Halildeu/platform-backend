package com.example.endpointadmin.controller;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.diff.DiffCacheBackfillResult;
import com.example.endpointadmin.service.diff.DiffCacheBackfillService;
import com.example.endpointadmin.service.diff.DiffType;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * BE-024c v2-c-pre-2-C-B MockMvc slice test for
 * {@link AdminDiffCacheController}
 * (Codex 019e8a09 iter-1 must-fix #5: controller had no MockMvc/authz
 * test in baseline).
 *
 * <p>Coverage:
 * <ul>
 *   <li>200 TENANT scope — body without deviceIds calls backfillTenant,
 *       response shape correct.</li>
 *   <li>200 DEVICES scope — body with deviceIds calls backfillBatch.</li>
 *   <li>400 malformed body — missing type → validation error.</li>
 *   <li>Tenant boundary — body cannot smuggle in another tenant; the
 *       resolved tenant from {@link TenantContextResolver} is what the
 *       service sees.</li>
 *   <li>OUTDATED type routing through the service correctly.</li>
 * </ul>
 *
 * <p>Local profile auth bypass — 401/403 coverage handled by the
 * authorization annotation reflection guard (separate test class).
 */
@WebMvcTest(AdminDiffCacheController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminDiffCacheControllerTest {

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_A =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID DEVICE_B =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DiffCacheBackfillService backfillService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void postBackfill_tenantScope_software_returnsOk() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(backfillService.backfillTenant(eq(TENANT_ID), eq(DiffType.SOFTWARE), eq(200)))
                .thenReturn(new DiffCacheBackfillResult(7L, 5L, 2L, 0L, 123L));

        mockMvc.perform(post("/api/v1/admin/diff-cache/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SOFTWARE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.type").value("SOFTWARE"))
                .andExpect(jsonPath("$.scope").value("TENANT"))
                .andExpect(jsonPath("$.checked").value(7))
                .andExpect(jsonPath("$.changed").value(5))
                .andExpect(jsonPath("$.unchanged").value(2))
                .andExpect(jsonPath("$.errors").value(0))
                .andExpect(jsonPath("$.elapsedMs").value(123));

        verify(backfillService, never()).backfillBatch(eq(TENANT_ID), eq(DiffType.SOFTWARE), eq(List.of()));
    }

    @Test
    void postBackfill_devicesScope_outdated_returnsOk() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(backfillService.backfillBatch(eq(TENANT_ID), eq(DiffType.OUTDATED),
                eq(List.of(DEVICE_A, DEVICE_B))))
                .thenReturn(new DiffCacheBackfillResult(2L, 2L, 0L, 0L, 80L));

        mockMvc.perform(post("/api/v1/admin/diff-cache/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"OUTDATED\","
                                + "\"deviceIds\":[\"" + DEVICE_A + "\",\"" + DEVICE_B + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("DEVICES"))
                .andExpect(jsonPath("$.checked").value(2))
                .andExpect(jsonPath("$.changed").value(2));
    }

    @Test
    void postBackfill_tenantScope_customPageSize_passedThrough() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(backfillService.backfillTenant(eq(TENANT_ID), eq(DiffType.SOFTWARE), eq(50)))
                .thenReturn(new DiffCacheBackfillResult(0L, 0L, 0L, 0L, 1L));

        mockMvc.perform(post("/api/v1/admin/diff-cache/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SOFTWARE\",\"pageSize\":50}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("TENANT"));

        verify(backfillService).backfillTenant(eq(TENANT_ID), eq(DiffType.SOFTWARE), eq(50));
    }

    @Test
    void postBackfill_missingType_returnsBadRequest() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());

        mockMvc.perform(post("/api/v1/admin/diff-cache/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postBackfill_unknownTypeEnum_returnsBadRequest() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());

        mockMvc.perform(post("/api/v1/admin/diff-cache/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"BOTH\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void postBackfill_emptyDeviceIds_treatedAsTenantScope() throws Exception {
        // Per controller contract: deviceIds == null OR empty → TENANT
        // scope (Codex iter-1 would flag if this regressed to DEVICES
        // scope with an empty batch call).
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(backfillService.backfillTenant(eq(TENANT_ID), eq(DiffType.SOFTWARE), eq(200)))
                .thenReturn(new DiffCacheBackfillResult(3L, 0L, 3L, 0L, 50L));

        mockMvc.perform(post("/api/v1/admin/diff-cache/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SOFTWARE\",\"deviceIds\":[]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scope").value("TENANT"));
    }

    @Test
    void postBackfill_tenantResolvedFromContext_notBody() throws Exception {
        // Body cannot smuggle in another tenant — resolved from
        // TenantContextResolver. We verify by stubbing the resolver to
        // return TENANT_ID and confirming the SERVICE sees TENANT_ID
        // regardless of what the body might say (this body doesn't try
        // to include tenantId since the DTO doesn't have that field,
        // but the test pins the contract that the resolver is the
        // authoritative source).
        UUID otherTenant = UUID.fromString("99999999-9999-9999-9999-999999999999");
        when(tenantContextResolver.resolveRequired()).thenReturn(adminContext());
        when(backfillService.backfillTenant(eq(TENANT_ID), eq(DiffType.SOFTWARE), eq(200)))
                .thenReturn(new DiffCacheBackfillResult(1L, 1L, 0L, 0L, 10L));
        when(backfillService.backfillTenant(eq(otherTenant), eq(DiffType.SOFTWARE), eq(200)))
                .thenReturn(new DiffCacheBackfillResult(99L, 99L, 0L, 0L, 999L));

        mockMvc.perform(post("/api/v1/admin/diff-cache/backfill")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"SOFTWARE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.checked").value(1));

        verify(backfillService).backfillTenant(eq(TENANT_ID), eq(DiffType.SOFTWARE), eq(200));
        verify(backfillService, never()).backfillTenant(eq(otherTenant), eq(DiffType.SOFTWARE), eq(200));
    }

    private static AdminTenantContext adminContext() {
        return new AdminTenantContext(TENANT_ID, "admin-subject");
    }
}

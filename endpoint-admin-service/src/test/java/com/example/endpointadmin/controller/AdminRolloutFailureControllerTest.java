package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.WaveFailureExportResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP contract for the failed-device queue read/export controller (#527). */
@WebMvcTest(AdminRolloutFailureController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminRolloutFailureControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RolloutFailureQueueReadService readService;
    @MockitoBean TenantContextResolver tenantContextResolver;

    private static final UUID TENANT = UUID.randomUUID();
    private static final String ROLLOUT = "rollout-2026-q3-agent-0.2.x";
    private static final String WAVE = "wave-02-pilot-50";
    private static final String PATH = "/api/v1/admin/endpoint-rollouts/" + ROLLOUT
            + "/waves/" + WAVE + "/failure-queue-export";

    @Test
    void waveExportReturnsContractShape() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(new AdminTenantContext(TENANT, "op@acik"));
        when(readService.waveExport(eq(TENANT), eq(ROLLOUT), eq(WAVE), any()))
                .thenReturn(sampleExport());

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schemaVersion").value("failed-device-queue/v1"))
                .andExpect(jsonPath("$.rollout_id").value(ROLLOUT))
                .andExpect(jsonPath("$.per_class_counts.SERVICE_HMAC_MODE").value(1))
                .andExpect(jsonPath("$.sample_items[0].org_id").value(TENANT.toString()))
                .andExpect(jsonPath("$.enforcement.live_ingest").value(true))
                .andExpect(jsonPath("$.enforcement.threshold_evaluator").value(true))
                .andExpect(jsonPath("$.enforcement.github_escalation_generator").value(true))
                .andExpect(jsonPath("$.enforcement.deployment_enforcement_active").value(false));
    }

    @Test
    void waveExportMissingSnapshotReturns409() throws Exception {
        when(tenantContextResolver.resolveRequired()).thenReturn(new AdminTenantContext(TENANT, "op@acik"));
        when(readService.waveExport(eq(TENANT), eq(ROLLOUT), eq(WAVE), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "metrics_snapshot_missing"));

        mockMvc.perform(get(PATH)).andExpect(status().isConflict());
    }

    private static WaveFailureExportResponse sampleExport() {
        UUID device = UUID.randomUUID();
        Map<String, Object> evidence = new HashMap<>();
        evidence.put("class", "SERVICE_HMAC_MODE");
        evidence.put("device_id", device.toString());
        evidence.put("service_state", "running");
        evidence.put("agent_mode", "hmac");
        evidence.put("hmac_error_code", "HMAC_CONN_RESET");
        evidence.put("last_heartbeat_at", "2026-06-29T04:00:00Z");
        evidence.put("command_id", null);
        evidence.put("agent_version", "0.2.0");
        WaveFailureExportResponse.Item item = new WaveFailureExportResponse.Item(
                UUID.randomUUID(), TENANT, ROLLOUT, WAVE, device,
                "SERVICE_HMAC_MODE", "new", 0, 2,
                Instant.parse("2026-06-29T04:00:00Z"),
                Instant.parse("2026-06-29T04:10:00Z"),
                Instant.parse("2026-06-29T04:10:00Z"),
                evidence, "platform-agent", true, null, null, null,
                null, null, null, "high", "auto:command-result:v1", 1L);
        return new WaveFailureExportResponse(
                "failed-device-queue/v1", ROLLOUT, WAVE,
                Instant.parse("2026-06-29T05:00:00Z"),
                10, 800, 1L, 10.0, 0L, 0.0,
                "stop_expansion",
                Map.of("DNS_EDGE_MTLS", 0L, "CERT_IDENTITY", 0L, "INSTALLER_MSI", 0L,
                        "SERVICE_HMAC_MODE", 1L, "BACKEND_RESULT_SUBMIT", 0L, "EDR_NETWORK", 0L),
                List.of(item), List.of(),
                new WaveFailureExportResponse.Enforcement(true, true, true, false));
    }
}

package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.WaveMetricsSnapshotResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutWaveMetricsSnapshotService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** §9.3 metrics-snapshot HTTP contract: 201 / missing-field 400 / invariant 400. */
@WebMvcTest(AdminRolloutWaveMetricsController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminRolloutWaveMetricsControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RolloutWaveMetricsSnapshotService service;
    @MockitoBean TenantContextResolver tenantContextResolver;

    private static final String PATH =
            "/api/v1/admin/endpoint-rollouts/rollout-2026-q3/waves/wave-02/metrics-snapshot";

    private String body(String activeWaveSize) {
        return """
                {"activeWaveSize": %s, "fleetSize": 800, "stale24hCount": 10,
                 "capturedAt": "2026-06-09T09:00:00Z", "sourceSnapshotId": "run-1"}"""
                .formatted(activeWaveSize);
    }

    @Test
    void recordReturns201() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(service.record(any(), any(), any(), any())).thenReturn(new WaveMetricsSnapshotResponse(
                UUID.randomUUID(), "rollout-2026-q3", "wave-02", 50, 800, 10,
                "orchestrator_snapshot", Instant.now(), Instant.now()));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("50")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.source").value("orchestrator_snapshot"));
    }

    @Test
    void missingActiveWaveSizeReturns400() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("null")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rawSourceSnapshotIdShapeReturns400() throws Exception {
        // an email/whitespace in the opaque ref must be rejected by @Pattern (non-PII)
        String body = """
                {"activeWaveSize": 50, "fleetSize": 800, "stale24hCount": 10,
                 "capturedAt": "2026-06-09T09:00:00Z", "sourceSnapshotId": "op@acik.com run"}""";
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void serviceInvariantViolationSurfacesAs400() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(service.record(any(), any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "fleetSize must be >= activeWaveSize"));
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("1000")))
                .andExpect(status().isBadRequest());
    }
}

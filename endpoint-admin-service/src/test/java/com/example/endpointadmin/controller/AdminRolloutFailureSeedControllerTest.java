package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureSeedResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueSeedService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the manual rollout-failure seed (#527 slice-1b). Local
 * profile bypasses auth (RBAC reflection is covered separately); pins
 * 201 / 400-missing-field / 400-evidence / 409-active-dup.
 */
@WebMvcTest(AdminRolloutFailureSeedController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminRolloutFailureSeedControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RolloutFailureQueueSeedService seedService;
    @MockitoBean TenantContextResolver tenantContextResolver;

    private static final UUID DEVICE = UUID.randomUUID();

    private String validBody() {
        return """
                {
                  "rolloutId": "rollout-2026-q3-agent-0.2.x", "waveId": "wave-02-pilot-50",
                  "deviceId": "%s", "failureClass": "DNS_EDGE_MTLS",
                  "classificationConfidence": "high",
                  "evidence": {
                    "class": "DNS_EDGE_MTLS", "endpoint_host_hash": "deadbeef",
                    "edge_target": "edge.acik.com:8443", "dns_error_code": null,
                    "tls_alert": null, "mtls_peer_cert_fingerprint_prefix": null,
                    "observed_at": "2026-06-09T05:30:00Z", "source": null
                  }
                }""".formatted(DEVICE);
    }

    private RolloutFailureSeedResponse sample() {
        return new RolloutFailureSeedResponse(UUID.randomUUID(), "rollout-2026-q3-agent-0.2.x",
                "wave-02-pilot-50", DEVICE, "DNS_EDGE_MTLS", "new", "manual:v1", Instant.now());
    }

    @Test
    void createReturns201() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(seedService.seedManual(any(), eq("op@acik"), any())).thenReturn(sample());
        mockMvc.perform(post("/api/v1/admin/endpoint-rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentState").value("new"))
                .andExpect(jsonPath("$.classifierVersion").value("manual:v1"));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        String body = validBody().replace("\"deviceId\": \"" + DEVICE + "\"", "\"deviceId\": null");
        mockMvc.perform(post("/api/v1/admin/endpoint-rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evidenceValidationFailureSurfacesAs400() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(seedService.seedManual(any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "evidence keys ..."));
        mockMvc.perform(post("/api/v1/admin/endpoint-rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activeDuplicateSurfacesAs409() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(seedService.seedManual(any(), any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "active dup"));
        mockMvc.perform(post("/api/v1/admin/endpoint-rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict());
    }
}

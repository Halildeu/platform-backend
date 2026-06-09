package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureResponse;
import com.example.endpointadmin.model.ClassificationConfidence;
import com.example.endpointadmin.model.RolloutFailureClass;
import com.example.endpointadmin.model.RolloutFailureState;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.EndpointRolloutFailureService;
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

/**
 * HTTP contract for the rollout failed-device manual-seed endpoint (Faz 22.5
 * #527 slice-1a). Local profile bypasses auth (RBAC is covered by the
 * annotation-reflection suite); this pins 201/400/409 + that the validator's
 * 400 surfaces. @MockitoBean isolates the controller from the service.
 */
@WebMvcTest(AdminEndpointRolloutFailureController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminEndpointRolloutFailureControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    EndpointRolloutFailureService service;

    @MockitoBean
    TenantContextResolver tenantContextResolver;

    private static final String ROLLOUT = "rollout-2026-q3-agent-0.2.x";
    private static final String WAVE = "wave-02-pilot-50";
    private static final UUID DEVICE = UUID.randomUUID();

    private String validBody() {
        return """
                {
                  "rolloutId": "%s", "waveId": "%s", "deviceId": "%s",
                  "failureClass": "DNS_EDGE_MTLS", "classificationConfidence": "high",
                  "evidence": {
                    "class": "DNS_EDGE_MTLS", "endpoint_host_hash": "deadbeef",
                    "edge_target": "edge.acik.com:8443", "dns_error_code": null,
                    "tls_alert": null, "mtls_peer_cert_fingerprint_prefix": null,
                    "observed_at": "2026-06-09T05:30:00Z", "source": null
                  }
                }""".formatted(ROLLOUT, WAVE, DEVICE);
    }

    private RolloutFailureResponse sample() {
        return new RolloutFailureResponse(UUID.randomUUID(), ROLLOUT, WAVE, DEVICE,
                RolloutFailureClass.DNS_EDGE_MTLS, RolloutFailureState.NEW, 0, 3,
                ClassificationConfidence.HIGH, "manual:v1", "platform/edge operator",
                null, null, Instant.now(), Instant.now(), Instant.now());
    }

    @Test
    void createReturns201() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(service.createManual(any(), any())).thenReturn(sample());
        mockMvc.perform(post("/api/v1/admin/rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.currentState").value("new"))
                .andExpect(jsonPath("$.classifierVersion").value("manual:v1"));
    }

    @Test
    void missingRequiredFieldReturns400() throws Exception {
        // null deviceId → @NotNull bean validation 400 at the controller layer
        String body = validBody().replace("\"deviceId\": \"" + DEVICE + "\"", "\"deviceId\": null");
        mockMvc.perform(post("/api/v1/admin/rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void evidenceValidationFailureSurfacesAs400() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(service.createManual(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "evidence keys ..."));
        mockMvc.perform(post("/api/v1/admin/rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void activeDuplicateSurfacesAs409() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(service.createManual(any(), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT, "active dup"));
        mockMvc.perform(post("/api/v1/admin/rollout-failures")
                        .contentType(MediaType.APPLICATION_JSON).content(validBody()))
                .andExpect(status().isConflict());
    }
}

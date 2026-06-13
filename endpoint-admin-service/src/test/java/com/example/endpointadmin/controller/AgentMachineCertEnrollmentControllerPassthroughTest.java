package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.AutoEnrollmentResponse;
import com.example.endpointadmin.security.TestX509Certs;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.5 Step-2 — passthrough tenant-authority (Codex plan-time thread
 * {@code 019ec0f9}). On the mTLS connector {@code X-Tenant-Id} is IGNORED and
 * the tenant is the fixed single-tenant UUID, so a forged tenant header cannot
 * steer a FIRST enrollment into an arbitrary tenant.
 */
@WebMvcTest(AgentMachineCertEnrollmentController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
@TestPropertySource(properties = {
        "endpoint-admin.mtls.forward-header.enabled=false",
        "endpoint-admin.mtls.passthrough.enabled=true",
        "endpoint-admin.mtls.passthrough.port=8443",
        "endpoint-admin.mtls.passthrough.fixed-tenant-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
})
class AgentMachineCertEnrollmentControllerPassthroughTest {

    private static final UUID FIXED_TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FORGED_TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final int MTLS_PORT = 8443;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService service;

    /** Simulate a request that arrived on the mTLS connector with a client cert. */
    private static RequestPostProcessor mtlsRequest(X509Certificate cert) {
        return request -> {
            request.setAttribute(
                    AgentMachineCertEnrollmentController.CERT_REQUEST_ATTRIBUTE,
                    new X509Certificate[]{cert});
            request.setLocalPort(MTLS_PORT);
            return request;
        };
    }

    private static String requestBody() {
        return """
                {
                  "machineFingerprint": "fp-passthrough-001",
                  "hostname": "DESKTOP-PT",
                  "osName": "windows",
                  "agentVersion": "0.1.1-lab.2",
                  "schemaVersion": 1
                }
                """;
    }

    private static MachineCertAutoEnrollService.Outcome enrolledOutcome() {
        UUID guid = UUID.randomUUID();
        return new MachineCertAutoEnrollService.Outcome(
                HttpStatus.CREATED,
                new AutoEnrollmentResponse(
                        UUID.randomUUID(),
                        "enrolled",
                        Instant.parse("2026-06-13T10:00:00Z"),
                        new AutoEnrollmentResponse.CertInfo(
                                "adcomputer:" + guid.toString().toLowerCase(),
                                guid,
                                "abc123",
                                Instant.parse("2027-06-13T10:00:00Z"))));
    }

    @Test
    void forgedTenantHeaderIgnored_firstEnrollUsesFixedTenant() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        when(service.autoEnroll(any(), any(UUID.class), any())).thenReturn(enrolledOutcome());

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, FORGED_TENANT.toString())
                        .content(requestBody())
                        .with(mtlsRequest(cert)))
                .andExpect(status().isCreated());

        // The service MUST be invoked with the FIXED tenant, never the forged header.
        ArgumentCaptor<UUID> tenant = ArgumentCaptor.forClass(UUID.class);
        verify(service).autoEnroll(any(), tenant.capture(), any());
        assertThat(tenant.getValue()).isEqualTo(FIXED_TENANT);
        assertThat(tenant.getValue()).isNotEqualTo(FORGED_TENANT);
    }

    @Test
    void noTenantHeader_passthroughStillEnrollsUnderFixedTenant() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        when(service.autoEnroll(any(), eq(FIXED_TENANT), any())).thenReturn(enrolledOutcome());

        // No X-Tenant-Id header at all — header is not required in passthrough.
        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .content(requestBody())
                        .with(mtlsRequest(cert)))
                .andExpect(status().isCreated());

        verify(service).autoEnroll(any(), eq(FIXED_TENANT), any());
    }

    @Test
    void repeatCrossTenant_serviceBoundaryStillEnforced() throws Exception {
        // Even with the fixed tenant, the service-level TENANT_BOUNDARY guard
        // (SAN already active under another tenant) still propagates as 403.
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        when(service.autoEnroll(any(), eq(FIXED_TENANT), any()))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_BOUNDARY"));

        mockMvc.perform(post("/api/v1/endpoint-agent/endpoint-enrollments/auto")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, FORGED_TENANT.toString())
                        .content(requestBody())
                        .with(mtlsRequest(cert)))
                .andExpect(status().isForbidden());

        verify(service).autoEnroll(any(), eq(FIXED_TENANT), any());
    }
}

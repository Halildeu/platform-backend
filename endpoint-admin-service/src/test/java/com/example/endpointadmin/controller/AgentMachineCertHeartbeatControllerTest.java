package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.AgentHeartbeatResponse;
import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.security.TestX509Certs;
import com.example.endpointadmin.service.EndpointHeartbeatService;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.5 M2-B — cert-auth heartbeat controller tests. This is the
 * tokenless path: no HMAC headers, bearer token or device credential auth is
 * sent; the presented machine cert is the device authority.
 */
@WebMvcTest(AgentMachineCertHeartbeatController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
@TestPropertySource(properties = {
        "endpoint-admin.mtls.forward-header.enabled=false",
        "endpoint-admin.mtls.passthrough.enabled=true",
        "endpoint-admin.mtls.passthrough.port=8443",
        "endpoint-admin.mtls.passthrough.fixed-tenant-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
})
class AgentMachineCertHeartbeatControllerTest {

    private static final UUID FIXED_TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FORGED_TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final int MTLS_PORT = 8443;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService certService;

    @MockitoBean
    private EndpointHeartbeatService heartbeatService;

    private static RequestPostProcessor mtlsRequest(X509Certificate cert) {
        return request -> {
            request.setAttribute(
                    AgentMachineCertEnrollmentController.CERT_REQUEST_ATTRIBUTE,
                    new X509Certificate[]{cert});
            request.setLocalPort(MTLS_PORT);
            return request;
        };
    }

    private static String heartbeatBody() {
        return """
                {
                  "installId": "install-1",
                  "hostname": "ERP-MOBIL",
                  "osFamily": "WINDOWS",
                  "architecture": "amd64",
                  "agentVersion": "0.2.0",
                  "osVersion": "Windows Server 2022",
                  "state": "ONLINE",
                  "capabilities": ["COLLECT_INVENTORY"],
                  "timestamp": "2026-06-14T18:00:00Z",
                  "inventory": {"source": "mtls"}
                }
                """;
    }

    @Test
    void passthroughHeartbeatUsesMachineCertAndIgnoresForgedTenantHeader() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        UUID deviceId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        DeviceCredentialResult principal = new DeviceCredentialResult(
                deviceId.toString(),
                "machine-cert:33333333-3333-3333-3333-333333333333",
                Instant.parse("2026-06-14T18:00:00Z")
        );

        when(certService.authenticateLifecycle(any(), eq(FIXED_TENANT))).thenReturn(principal);
        when(heartbeatService.recordHeartbeat(eq(principal), any(), eq("127.0.0.1"), eq("mtls-machine-cert")))
                .thenReturn(new AgentHeartbeatResponse(
                        true,
                        deviceId,
                        DeviceStatus.ONLINE,
                        Instant.parse("2026-06-14T18:00:01Z")
                ));

        mockMvc.perform(post("/api/v1/endpoint-agent/heartbeat")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, FORGED_TENANT.toString())
                        .content(heartbeatBody())
                        .with(mtlsRequest(cert)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accepted").value(true))
                .andExpect(jsonPath("$.deviceId").value(deviceId.toString()))
                .andExpect(jsonPath("$.status").value("ONLINE"));

        ArgumentCaptor<UUID> tenant = ArgumentCaptor.forClass(UUID.class);
        verify(certService).authenticateLifecycle(any(), tenant.capture());
        assertThat(tenant.getValue()).isEqualTo(FIXED_TENANT);
        assertThat(tenant.getValue()).isNotEqualTo(FORGED_TENANT);
        verify(heartbeatService).recordHeartbeat(eq(principal), any(), eq("127.0.0.1"), eq("mtls-machine-cert"));
    }

    @Test
    void missingClientCertReturns401() throws Exception {
        mockMvc.perform(post("/api/v1/endpoint-agent/heartbeat")
                        .contentType("application/json")
                        .content(heartbeatBody()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void malformedHeartbeatBodyStillUsesStandardValidation() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());

        mockMvc.perform(post("/api/v1/endpoint-agent/heartbeat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "state": "ONLINE"
                                }
                                """)
                        .with(mtlsRequest(cert)))
                .andExpect(status().isBadRequest());
    }
}

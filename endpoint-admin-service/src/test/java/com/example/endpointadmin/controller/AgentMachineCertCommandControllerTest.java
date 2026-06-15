package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.agent.AgentCommandResponse;
import com.example.endpointadmin.model.CommandType;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.security.TestX509Certs;
import com.example.endpointadmin.service.EndpointAgentCommandService;
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

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22.5 M2-C — cert-auth command lifecycle controller tests. The path is
 * tokenless: no HMAC Authorization header and no bearer JWT are required.
 */
@WebMvcTest(AgentMachineCertCommandController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
@TestPropertySource(properties = {
        "endpoint-admin.mtls.forward-header.enabled=false",
        "endpoint-admin.mtls.passthrough.enabled=true",
        "endpoint-admin.mtls.passthrough.port=8443",
        "endpoint-admin.mtls.passthrough.fixed-tenant-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
})
class AgentMachineCertCommandControllerTest {

    private static final UUID FIXED_TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID FORGED_TENANT = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
    private static final UUID HEADER_TENANT = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
    private static final int MTLS_PORT = 8443;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService certService;

    @MockitoBean
    private EndpointAgentCommandService commandService;

    private static RequestPostProcessor mtlsRequest(X509Certificate cert) {
        return request -> {
            request.setAttribute(
                    AgentMachineCertEnrollmentController.CERT_REQUEST_ATTRIBUTE,
                    new X509Certificate[]{cert});
            request.setLocalPort(MTLS_PORT);
            return request;
        };
    }

    @Test
    void nextCommandUsesMachineCertAndIgnoresForgedTenantHeader() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        DeviceCredentialResult principal = principal();
        UUID commandId = UUID.fromString("33333333-3333-3333-3333-333333333333");

        when(certService.authenticateLifecycle(any(), eq(FIXED_TENANT))).thenReturn(principal);
        when(commandService.claimNext(eq(principal))).thenReturn(Optional.of(new AgentCommandResponse(
                commandId,
                "claim-1",
                2,
                CommandType.COLLECT_INVENTORY,
                "admin@example.com",
                "inventory refresh",
                Map.of("reason", "inventory refresh"),
                Instant.parse("2026-06-14T19:00:00Z")
        )));

        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, FORGED_TENANT.toString())
                        .with(mtlsRequest(cert)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.commandId").value(commandId.toString()))
                .andExpect(jsonPath("$.claimId").value("claim-1"))
                .andExpect(jsonPath("$.attemptNumber").value(2))
                .andExpect(jsonPath("$.type").value("COLLECT_INVENTORY"))
                .andExpect(jsonPath("$.reason").value("inventory refresh"));

        ArgumentCaptor<UUID> tenant = ArgumentCaptor.forClass(UUID.class);
        verify(certService).authenticateLifecycle(any(), tenant.capture());
        assertThat(tenant.getValue()).isEqualTo(FIXED_TENANT);
        assertThat(tenant.getValue()).isNotEqualTo(FORGED_TENANT);
        verify(commandService).claimNext(eq(principal));
    }

    @Test
    void nextCommandReturnsNoContentWhenQueueEmpty() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        DeviceCredentialResult principal = principal();

        when(certService.authenticateLifecycle(any(), eq(FIXED_TENANT))).thenReturn(principal);
        when(commandService.claimNext(eq(principal))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next")
                        .with(mtlsRequest(cert)))
                .andExpect(status().isNoContent());
    }

    @Test
    void nextCommandUsesExplicitTenantHeaderOutsidePassthroughPort() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        DeviceCredentialResult principal = principal();

        when(certService.authenticateLifecycle(any(), eq(HEADER_TENANT))).thenReturn(principal);
        when(commandService.claimNext(eq(principal))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, HEADER_TENANT.toString())
                        .with(request -> {
                            mtlsRequest(cert).postProcessRequest(request);
                            request.setLocalPort(8080);
                            return request;
                        }))
                .andExpect(status().isNoContent());

        verify(certService).authenticateLifecycle(any(), eq(HEADER_TENANT));
    }

    @Test
    void submitResultUsesMachineCertPrincipal() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        DeviceCredentialResult principal = principal();
        UUID commandId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        when(certService.authenticateLifecycle(any(), eq(FIXED_TENANT))).thenReturn(principal);

        mockMvc.perform(post("/api/v1/endpoint-agent/commands/{commandId}/result", commandId)
                        .contentType("application/json")
                        .content("""
                                {
                                  "claimId": "claim-1",
                                  "attemptNumber": 1,
                                  "status": "SUCCEEDED",
                                  "summary": "done",
                                  "details": {"inventory": {"hostname": "ERP-MOBIL"}},
                                  "exitCode": 0,
                                  "startedAt": "2026-06-14T19:00:00Z",
                                  "finishedAt": "2026-06-14T19:00:03Z"
                                }
                                """)
                        .with(mtlsRequest(cert)))
                .andExpect(status().isAccepted());

        verify(commandService).submitResult(eq(principal), eq(commandId), any());
    }

    @Test
    void missingClientCertReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void forwardedPemHeaderIsIgnoredWhenModeDisabled() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());

        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, HEADER_TENANT.toString())
                        .header(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER, encodedPem(cert)))
                .andExpect(status().isUnauthorized());
    }

    private DeviceCredentialResult principal() {
        return new DeviceCredentialResult(
                "22222222-2222-2222-2222-222222222222",
                "machine-cert:55555555-5555-5555-5555-555555555555",
                Instant.parse("2026-06-14T19:00:00Z")
        );
    }

    private static String encodedPem(X509Certificate cert) throws Exception {
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder().encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        return java.net.URLEncoder.encode(pem, StandardCharsets.UTF_8);
    }
}

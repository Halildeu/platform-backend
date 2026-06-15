package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.security.DeviceCredentialResult;
import com.example.endpointadmin.security.TestX509Certs;
import com.example.endpointadmin.service.EndpointAgentCommandService;
import com.example.endpointadmin.service.MachineCertAutoEnrollService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Forwarded-header mode is for trusted gateway termination only. This covers
 * the command controller's PEM parse branch; runtime deployment still depends
 * on the gateway stripping client-supplied X-Client-Cert before backend hop.
 */
@WebMvcTest(AgentMachineCertCommandController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
@TestPropertySource(properties = {
        "endpoint-admin.mtls.forward-header.enabled=true",
        "endpoint-admin.mtls.passthrough.enabled=false"
})
class AgentMachineCertCommandControllerForwardHeaderTest {

    private static final UUID TENANT = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService certService;

    @MockitoBean
    private EndpointAgentCommandService commandService;

    @Test
    void forwardedPemHeaderCanAuthenticateCommandPoll() throws Exception {
        X509Certificate cert = TestX509Certs.validClientCert(UUID.randomUUID());
        DeviceCredentialResult principal = new DeviceCredentialResult(
                "22222222-2222-2222-2222-222222222222",
                "machine-cert:55555555-5555-5555-5555-555555555555",
                Instant.parse("2026-06-14T19:00:00Z")
        );

        when(certService.authenticateLifecycle(any(), eq(TENANT))).thenReturn(principal);
        when(commandService.claimNext(eq(principal))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT.toString())
                        .header(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER, encodedPem(cert)))
                .andExpect(status().isNoContent());

        verify(certService).authenticateLifecycle(any(), eq(TENANT));
    }

    @Test
    void malformedForwardedPemHeaderReturns401() throws Exception {
        String malformed = java.net.URLEncoder.encode(
                "-----BEGIN CERTIFICATE-----\nnot-a-cert\n-----END CERTIFICATE-----\n",
                StandardCharsets.UTF_8);

        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT.toString())
                        .header(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER, malformed))
                .andExpect(status().isUnauthorized());
    }

    private static String encodedPem(X509Certificate cert) throws Exception {
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder().encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        return java.net.URLEncoder.encode(pem, StandardCharsets.UTF_8);
    }
}

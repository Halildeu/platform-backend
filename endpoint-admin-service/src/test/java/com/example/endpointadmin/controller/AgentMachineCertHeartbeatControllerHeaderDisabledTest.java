package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.security.TestX509Certs;
import com.example.endpointadmin.service.EndpointHeartbeatService;
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
import java.util.Base64;
import java.util.UUID;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Faz 22 #1497 (ADR-0029 §2.5 reconciliation, Codex 019f0056 must-fix #2) — N2 spoof-deny
 * symmetry for the heartbeat surface. The enrollment + command surfaces already assert it;
 * this closes the heartbeat gap.
 *
 * <p>With forwarded-header mode DISABLED (now the default in EVERY profile — #1497) and NO
 * servlet mTLS attribute (direct off-edge call), a caller injecting an {@code X-Client-Cert}
 * header MUST be rejected with 401 even if the header carries a structurally valid PEM. The
 * header is never a trusted identity source unless the operator explicitly opts into the
 * hardened lab fallback. No device lifecycle authentication may occur.
 */
@WebMvcTest(AgentMachineCertHeartbeatController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
@TestPropertySource(properties = "endpoint-admin.mtls.forward-header.enabled=false")
class AgentMachineCertHeartbeatControllerHeaderDisabledTest {

    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService certService;

    @MockitoBean
    private EndpointHeartbeatService heartbeatService;

    private static String heartbeatBody() {
        return """
                {
                  "installId": "install-spoof",
                  "hostname": "ERP-SPOOF",
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
    void forwardedHeaderRejectedWhenModeDisabled() throws Exception {
        UUID guid = UUID.randomUUID();
        X509Certificate cert = TestX509Certs.validClientCert(guid);
        String pem = "-----BEGIN CERTIFICATE-----\n"
                + Base64.getMimeEncoder().encodeToString(cert.getEncoded())
                + "\n-----END CERTIFICATE-----\n";
        String urlEncoded = java.net.URLEncoder.encode(pem, StandardCharsets.UTF_8);

        // No servlet mTLS attribute (direct off-edge), only a spoofed header — mode is OFF.
        // Must surface 401 and authenticate nothing.
        mockMvc.perform(post("/api/v1/endpoint-agent/heartbeat")
                        .contentType("application/json")
                        .header(AgentMachineCertEnrollmentController.TENANT_HEADER, TENANT_A.toString())
                        .header(AgentMachineCertEnrollmentController.CERT_FORWARD_HEADER, urlEncoded)
                        .content(heartbeatBody()))
                .andExpect(status().isUnauthorized());

        // The spoofed cert never reaches device authentication or heartbeat persistence.
        verifyNoInteractions(certService, heartbeatService);
    }
}

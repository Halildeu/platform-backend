package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.endpointadmin.config.MtlsSecurityConfig;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard for the non-local security chain. The controller unit test
 * imports SecurityConfigLocal, so it cannot catch an mTLS chain denyAll drift.
 */
@WebMvcTest(AgentMachineCertHeartbeatController.class)
@ActiveProfiles("test")
@Import(MtlsSecurityConfig.class)
@TestPropertySource(properties = {
        "endpoint-admin.mtls.forward-header.enabled=false",
        "endpoint-admin.mtls.passthrough.enabled=true",
        "endpoint-admin.mtls.passthrough.port=8443",
        "endpoint-admin.mtls.passthrough.fixed-tenant-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
})
class AgentMachineCertHeartbeatSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService certService;

    @MockitoBean
    private EndpointHeartbeatService heartbeatService;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @Test
    void mtlsSecurityChainPermitsHeartbeatInsteadOfDenyingAll() throws Exception {
        mockMvc.perform(post("/api/v1/endpoint-agent/heartbeat")
                        .contentType("application/json")
                        .content("""
                                {
                                  "installId": "install-1",
                                  "hostname": "ERP-MOBIL",
                                  "osFamily": "WINDOWS",
                                  "architecture": "amd64",
                                  "agentVersion": "0.2.0",
                                  "osVersion": "Windows Server 2022",
                                  "state": "ONLINE",
                                  "capabilities": ["COLLECT_INVENTORY"],
                                  "timestamp": "2026-06-14T18:00:00Z"
                                }
                                """))
                // 401 means the request passed the authorization denyAll guard
                // and reached mTLS cert resolution. A 403 would be the regression.
                .andExpect(status().isUnauthorized());
    }
}

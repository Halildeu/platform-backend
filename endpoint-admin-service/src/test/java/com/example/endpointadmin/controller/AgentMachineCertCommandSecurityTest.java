package com.example.endpointadmin.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.endpointadmin.config.MtlsSecurityConfig;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression guard for the non-local mTLS security chain. A 401 means the
 * request reached controller cert resolution; a 403 means the denyAll fallback
 * caught the path before M2-C could run.
 */
@WebMvcTest(AgentMachineCertCommandController.class)
@ActiveProfiles("test")
@Import(MtlsSecurityConfig.class)
@TestPropertySource(properties = {
        "endpoint-admin.mtls.forward-header.enabled=false",
        "endpoint-admin.mtls.passthrough.enabled=true",
        "endpoint-admin.mtls.passthrough.port=8443",
        "endpoint-admin.mtls.passthrough.fixed-tenant-id=aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"
})
class AgentMachineCertCommandSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MachineCertAutoEnrollService certService;

    @MockitoBean
    private EndpointAgentCommandService commandService;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @Test
    void mtlsSecurityChainPermitsCommandPollInsteadOfDenyingAll() throws Exception {
        mockMvc.perform(get("/api/v1/endpoint-agent/commands/next"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mtlsSecurityChainPermitsCommandResultInsteadOfDenyingAll() throws Exception {
        mockMvc.perform(post("/api/v1/endpoint-agent/commands/44444444-4444-4444-4444-444444444444/result")
                        .contentType("application/json")
                        .content("""
                                {
                                  "claimId": "claim-1",
                                  "attemptNumber": 1,
                                  "status": "SUCCEEDED"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mtlsSecurityChainDeniesUnsupportedCommandMethods() throws Exception {
        mockMvc.perform(put("/api/v1/endpoint-agent/commands/next"))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/endpoint-agent/commands/44444444-4444-4444-4444-444444444444/result"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/endpoint-agent/commands/44444444-4444-4444-4444-444444444444/result"))
                .andExpect(status().isForbidden());
    }
}

package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureDetailResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEscalationGenerator;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** §9.4 escalation-issue GET contract: 200 (generated) / 404 (unknown). */
@WebMvcTest(AdminRolloutFailureEscalationController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminRolloutFailureEscalationControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RolloutFailureQueueReadService readService;
    @MockitoBean RolloutFailureEscalationGenerator generator;
    @MockitoBean TenantContextResolver tenantContextResolver;

    private static final UUID FAILURE = UUID.randomUUID();
    private static final String PATH =
            "/api/v1/admin/endpoint-rollout-failures/" + FAILURE + "/escalation-issue";

    @Test
    void generatesEscalationIssue200() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(readService.getDetail(any(), any()))
                .thenReturn(Optional.of(new RolloutFailureDetailResponse(null, List.of())));
        when(generator.generate(any())).thenReturn(new RolloutFailureEscalationResponse(
                "Rollout Failure Escalation — INSTALLER_MSI / wave-02", "body...",
                List.of("rollout-failure", "class:INSTALLER_MSI"), FAILURE));
        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issueTitle").value("Rollout Failure Escalation — INSTALLER_MSI / wave-02"))
                .andExpect(jsonPath("$.labels[0]").value("rollout-failure"));
    }

    @Test
    void unknownFailureReturns404() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(readService.getDetail(any(), any())).thenReturn(Optional.empty());
        mockMvc.perform(get(PATH)).andExpect(status().isNotFound());
    }
}

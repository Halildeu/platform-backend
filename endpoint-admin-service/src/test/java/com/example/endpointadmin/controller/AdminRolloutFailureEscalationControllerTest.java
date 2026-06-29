package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureDetailResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationPublishResponse;
import com.example.endpointadmin.dto.v1.admin.RolloutFailureEscalationResponse;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.RolloutFailureQueueReadService;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEscalationGenerator;
import com.example.endpointadmin.service.rolloutfailure.RolloutFailureEscalationPublishService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.HttpHeaders;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** §9.4 escalation-issue GET contract: 200 (generated) / 404 (unknown). */
@WebMvcTest(AdminRolloutFailureEscalationController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminRolloutFailureEscalationControllerTest {

    @Autowired MockMvc mockMvc;

    @MockitoBean RolloutFailureQueueReadService readService;
    @MockitoBean RolloutFailureEscalationGenerator generator;
    @MockitoBean RolloutFailureEscalationPublishService publishService;
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

    @Test
    void publishesEscalationIssue201() throws Exception {
        UUID tenant = UUID.randomUUID();
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(tenant, "op@acik"));
        when(publishService.publish(any(), any(), any())).thenReturn(
                new RolloutFailureEscalationPublishResponse(FAILURE,
                        "https://github.com/Halildeu/platform-backend/issues/9001",
                        9001L,
                        "Rollout Failure Escalation — INSTALLER_MSI / wave-02",
                        List.of("rollout-failure", "class:INSTALLER_MSI"),
                        "escalated",
                        false));

        mockMvc.perform(post(PATH))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andExpect(header().string(HttpHeaders.PRAGMA, "no-cache"))
                .andExpect(jsonPath("$.issueUrl")
                        .value("https://github.com/Halildeu/platform-backend/issues/9001"))
                .andExpect(jsonPath("$.issueNumber").value(9001))
                .andExpect(jsonPath("$.currentState").value("escalated"))
                .andExpect(jsonPath("$.alreadyPublished").value(false));
        verify(publishService).publish(tenant, "op@acik", FAILURE);
    }

    @Test
    void alreadyPublishedReturns200() throws Exception {
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(UUID.randomUUID(), "op@acik"));
        when(publishService.publish(any(), any(), any())).thenReturn(
                new RolloutFailureEscalationPublishResponse(FAILURE,
                        "https://github.com/Halildeu/platform-backend/issues/9001",
                        null,
                        "Rollout Failure Escalation — INSTALLER_MSI / wave-02",
                        List.of("rollout-failure"),
                        "escalated",
                        true));

        mockMvc.perform(post(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyPublished").value(true));
    }
}

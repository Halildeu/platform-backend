package com.example.endpointadmin.controller;

import com.example.endpointadmin.config.SecurityConfigLocal;
import com.example.endpointadmin.domainops.DomainOpsResult;
import com.example.endpointadmin.domainops.DomainOpsStatus;
import com.example.endpointadmin.security.AdminTenantContext;
import com.example.endpointadmin.security.TenantContextResolver;
import com.example.endpointadmin.service.DomainOpsBrokerService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminDomainOpsController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class AdminDomainOpsControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID OPERATION_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DomainOpsBrokerService domainOpsBrokerService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void createDomainOpsRequestReturnsBrokerResponse() throws Exception {
        AdminTenantContext context = new AdminTenantContext(TENANT_ID, "admin@example.com");
        when(tenantContextResolver.resolveRequired()).thenReturn(context);
        when(domainOpsBrokerService.create(eq(context), eq(DEVICE_ID), any()))
                .thenReturn(new DomainOpsResult(
                        OPERATION_ID,
                        TENANT_ID,
                        DEVICE_ID,
                        "DOMAIN_SECURE_CHANNEL_VERIFY",
                        DomainOpsStatus.PENDING_DISPATCH,
                        "awaiting-domain-connector",
                        300,
                        "admin@example.com",
                        Instant.parse("2026-06-16T19:00:00Z"),
                        Instant.parse("2026-06-16T19:05:00Z"),
                        "domain-broker",
                        "attempt-001"));

        mockMvc.perform(post("/api/v1/admin/endpoint-devices/{deviceId}/domain-ops", DEVICE_ID)
                        .contentType("application/json")
                        .content("""
                                {
                                  "operation": "DOMAIN_SECURE_CHANNEL_VERIFY",
                                  "reason": "pilot secure channel smoke",
                                  "ttlSeconds": 300,
                                  "idempotencyKey": "domops-smoke-001",
                                  "credentialRef": "vault:domain-ops/pilot"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(OPERATION_ID.toString()))
                .andExpect(jsonPath("$.deviceId").value(DEVICE_ID.toString()))
                .andExpect(jsonPath("$.operation").value("DOMAIN_SECURE_CHANNEL_VERIFY"))
                .andExpect(jsonPath("$.status").value("PENDING_DISPATCH"))
                .andExpect(jsonPath("$.ttlSeconds").value(300))
                .andExpect(jsonPath("$.expiresAt").value("2026-06-16T19:05:00Z"))
                .andExpect(jsonPath("$.connectorName").value("domain-broker"))
                .andExpect(jsonPath("$.connectorAttemptId").value("attempt-001"));

        verify(domainOpsBrokerService).create(eq(context), eq(DEVICE_ID), any());
    }
}

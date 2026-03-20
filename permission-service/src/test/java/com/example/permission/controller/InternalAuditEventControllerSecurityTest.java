package com.example.permission.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import com.example.permission.model.PermissionAuditEvent;
import com.example.permission.security.SecurityConfig;
import com.example.permission.service.AuditEventService;

@WebMvcTest(controllers = InternalAuditEventController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = {
        "security.internal-api-key.enabled=true",
        "security.internal-api-key.value=test-internal-key"
})
class InternalAuditEventControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditEventService auditEventService;

    @Test
    void ingestEvent_requiresInternalApiKey() throws Exception {
        mockMvc.perform(post("/api/v1/internal/audit/events")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventType": "SESSION_CREATED",
                                  "performedBy": 99,
                                  "userEmail": "user@example.com",
                                  "service": "auth-service",
                                  "level": "INFO",
                                  "action": "SESSION_CREATED"
                                }
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void ingestEvent_acceptsValidInternalApiKey() throws Exception {
        PermissionAuditEvent saved = new PermissionAuditEvent();
        saved.setId(77L);
        saved.setOccurredAt(Instant.parse("2026-03-13T20:00:00Z"));
        when(auditEventService.recordMirroredEvent(any())).thenReturn(saved);

        mockMvc.perform(post("/api/v1/internal/audit/events")
                        .header("X-Internal-Api-Key", "test-internal-key")
                        .contentType("application/json")
                        .content("""
                                {
                                  "eventType": "SESSION_CREATED",
                                  "performedBy": 99,
                                  "userEmail": "user@example.com",
                                  "service": "auth-service",
                                  "level": "INFO",
                                  "action": "SESSION_CREATED",
                                  "details": "Session created for user@example.com in company scope 42"
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("accepted"))
                .andExpect(jsonPath("$.auditId").value("77"));
    }
}

package com.example.permission.controller;

import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.service.AuditEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditEventController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(roles = "ADMIN")
class AuditEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private AuditEventService auditEventService;

    @Test
    void getByIdReturnsSingleEvent() throws Exception {
        AuditEventResponse event = new AuditEventResponse(
                "123",
                Instant.parse("2025-01-01T00:00:00Z"),
                "a***@example.com",
                "permission-service",
                "INFO",
                "EXPORT_USERS",
                "Users export requested",
                "corr-42",
                Map.of("userId", 10),
                Map.of(),
                Map.of()
        );
        AuditEventPageResponse page = new AuditEventPageResponse(List.of(event), 0, 1);
        when(auditEventService.findByIdPage("123")).thenReturn(page);

        mockMvc.perform(get("/api/audit/events")
                        .param("id", "123")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events[0].id").value("123"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.total").value(1));
    }
}

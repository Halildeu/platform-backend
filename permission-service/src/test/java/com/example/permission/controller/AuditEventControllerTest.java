package com.example.permission.controller;

import com.example.permission.dto.AuditEventPageResponse;
import com.example.permission.dto.AuditEventResponse;
import com.example.permission.dto.v1.AuditExportJobResponseDto;
import com.example.permission.service.AuditEventService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.test.context.support.WithMockUser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = AuditEventController.class)
@AutoConfigureMockMvc(addFilters = false)
@WithMockUser(username = "admin", roles = "ADMIN")
class AuditEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
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

    @Test
    void createExportJobReturnsCreated() throws Exception {
        AuditExportJobResponseDto response = new AuditExportJobResponseDto(
                "job-1",
                "COMPLETED",
                "csv",
                "audit-events-job-1.csv",
                "text/csv",
                3,
                "admin@example.com",
                Instant.parse("2026-03-14T10:00:00Z"),
                Instant.parse("2026-03-14T10:00:01Z"),
                null,
                "/api/audit/events/export-jobs/job-1/download"
        );
        when(auditEventService.createExportJob(org.mockito.Mockito.anyString(), org.mockito.Mockito.anyString(), org.mockito.Mockito.any(), org.mockito.Mockito.any(), org.mockito.Mockito.anyMap()))
                .thenReturn(response);

        mockMvc.perform(post("/api/audit/events/export-jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"format":"csv","sort":"timestamp,desc","filters":{"service":"permission-service"}}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("job-1"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.downloadPath").value("/api/audit/events/export-jobs/job-1/download"));
    }

    @Test
    void downloadExportJobReturnsAttachment() throws Exception {
        com.example.permission.model.AuditExportJob job = new com.example.permission.model.AuditExportJob();
        job.setId("job-2");
        job.setFilename("audit-events-job-2.json");
        job.setContentType("application/json");
        job.setPayload("[]".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(auditEventService.getCompletedExportJob(eq("job-2"), anyString()))
                .thenReturn(job);

        mockMvc.perform(get("/api/audit/events/export-jobs/job-2/download"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=audit-events-job-2.json"));
    }
}

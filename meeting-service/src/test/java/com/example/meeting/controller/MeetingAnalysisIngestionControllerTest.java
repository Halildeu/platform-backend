package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.meeting.config.SecurityConfigLocal;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestionResponse;
import com.example.meeting.service.MeetingAnalysisIngestionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeetingAnalysisIngestionController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class MeetingAnalysisIngestionControllerTest {

    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private MeetingAnalysisIngestionService meetingAnalysisIngestionService;

    @Test
    void ingest_missingIdempotencyKeyHeader_returns400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/internal/meetings/{meetingId}/analysis-results", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("run-1"))))
                .andExpect(status().isBadRequest());

        verify(meetingAnalysisIngestionService, never()).ingest(any(), any());
    }

    @Test
    void ingest_idempotencyKeyMismatchWithBody_returns400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/internal/meetings/{meetingId}/analysis-results", MEETING_ID)
                        .header("Idempotency-Key", "run-DIFFERENT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("run-1"))))
                .andExpect(status().isBadRequest());

        verify(meetingAnalysisIngestionService, never()).ingest(any(), any());
    }

    @Test
    void ingest_validRequest_delegatesToServiceAndReturnsResponse() throws Exception {
        when(meetingAnalysisIngestionService.ingest(eq(MEETING_ID), any()))
                .thenReturn(new MeetingAnalysisResultIngestionResponse("run-1", MEETING_ID, false));

        mockMvc.perform(post("/api/v1/internal/meetings/{meetingId}/analysis-results", MEETING_ID)
                        .header("Idempotency-Key", "run-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body("run-1"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.analysis_run_id").value("run-1"))
                .andExpect(jsonPath("$.replayed").value(false));
    }

    private static java.util.Map<String, Object> body(String analysisRunId) {
        return java.util.Map.ofEntries(
                java.util.Map.entry("meetingId", MEETING_ID.toString()),
                java.util.Map.entry("analysis_run_id", analysisRunId),
                java.util.Map.entry("transcript_id", "transcript-1"),
                java.util.Map.entry("transcript_revision", "rev-1"),
                java.util.Map.entry("analyzer_contract_version", "1.0"),
                java.util.Map.entry("summary", "Ozet."),
                java.util.Map.entry("decisions", List.of()),
                java.util.Map.entry("actions", List.of()),
                java.util.Map.entry("citations", List.of()),
                java.util.Map.entry("rejected_claims", List.of()),
                java.util.Map.entry("generated_at", Instant.parse("2026-07-10T10:00:00Z").toString()));
    }
}

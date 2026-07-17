package com.example.transcript.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.transcript.config.SecurityConfigLocal;
import com.example.transcript.dto.TranscriptFinalizationDto;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.TenantContextResolver;
import com.example.transcript.service.TranscriptFinalizationService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(TranscriptFinalizationController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class TranscriptFinalizationControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID FINALIZATION = UUID.randomUUID();
    private static final String PATH =
            "/api/v1/admin/transcripts/meetings/{meetingId}/sessions/{sessionId}/finalizations";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private TranscriptFinalizationService service;
    @MockitoBean private TenantContextResolver tenants;

    @BeforeEach
    void setUp() {
        when(tenants.resolveRequired()).thenReturn(new AdminTenantContext(TENANT, "admin"));
    }

    @Test
    void explicitVersionFinalizesCanonicalSession() throws Exception {
        String eventKey = "meeting.transcript|" + SESSION + "|meeting.transcript.ready|1";
        when(service.finalizeTranscript(any(), eq(MEETING), eq(SESSION), eq(1L)))
                .thenReturn(new TranscriptFinalizationDto(
                        FINALIZATION, MEETING, SESSION, 1L, 2,
                        Instant.parse("2026-07-17T13:00:00Z"), eventKey));

        mockMvc.perform(post(PATH, MEETING, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalizationVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(FINALIZATION.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.finalizationVersion").value(1))
                .andExpect(jsonPath("$.segmentCount").value(2))
                .andExpect(jsonPath("$.eventKey").value(eventKey));
        verify(service).finalizeTranscript(any(), eq(MEETING), eq(SESSION), eq(1L));
    }

    @Test
    void zeroVersionIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post(PATH, MEETING, SESSION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"finalizationVersion\":0}"))
                .andExpect(status().isBadRequest());
    }
}

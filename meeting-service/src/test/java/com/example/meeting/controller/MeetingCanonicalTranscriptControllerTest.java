package com.example.meeting.controller;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.meeting.config.SecurityConfigLocal;
import com.example.meeting.dto.v1.admin.CanonicalMeetingTranscriptResponse;
import com.example.meeting.dto.v1.admin.CanonicalMeetingTranscriptSegment;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingCanonicalTranscriptService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeetingCanonicalTranscriptController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class MeetingCanonicalTranscriptControllerTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingCanonicalTranscriptService service;
    @MockitoBean private TenantContextResolver tenantContextResolver;

    @Test
    void exactRunPathReturnsNoStoreCanonicalContentContract() throws Exception {
        AdminTenantContext tenant = new AdminTenantContext(TENANT, "stable-sub", "stable-sub");
        when(tenantContextResolver.resolveRequired()).thenReturn(tenant);
        when(service.read(tenant, MEETING, RUN)).thenReturn(new CanonicalMeetingTranscriptResponse(
                RUN, MEETING, SESSION, 7L, Instant.parse("2026-07-18T12:00:00Z"),
                "FINALIZED", "canonical text", "a".repeat(64), 1,
                List.of(new CanonicalMeetingTranscriptSegment("canonical text", 0.0, 1.0))));

        mockMvc.perform(get(
                        "/api/v1/admin/meetings/{meetingId}/intelligence/results/{analysisRunId}/transcript",
                        MEETING, RUN))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.analysisRunId").value(RUN.toString()))
                .andExpect(jsonPath("$.meetingId").value(MEETING.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.finalizationVersion").value(7))
                .andExpect(jsonPath("$.transcriptSha256").value("a".repeat(64)))
                .andExpect(jsonPath("$.segments[0].text").value("canonical text"))
                .andExpect(jsonPath("$.tenantId").doesNotExist());

        verify(service).read(tenant, MEETING, RUN);
    }
}

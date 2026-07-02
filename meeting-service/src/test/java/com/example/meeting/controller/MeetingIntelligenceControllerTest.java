package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.meeting.config.SecurityConfigLocal;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceActionItem;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingIntelligenceService;
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

@WebMvcTest(MeetingIntelligenceController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class MeetingIntelligenceControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingIntelligenceService meetingIntelligenceService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @Test
    void analyze_postsTranscriptSourceToService() throws Exception {
        AdminTenantContext tenant = tenant();
        when(meetingIntelligenceService.analyze(eq(tenant), eq(MEETING_ID), any()))
                .thenReturn(new MeetingIntelligenceAnalyzeResponse(
                        "5-adr0043",
                        "verified_only",
                        "Recorder akisi dogrulandi.",
                        "verified",
                        List.of(),
                        List.of("Direct STT preview backend uzerinden izlenecek."),
                        List.of(new MeetingIntelligenceActionItem("Gateway route kaniti ekle.", "platform", null)),
                        List.of(),
                        List.of(),
                        0,
                        true,
                        1,
                        "mock",
                        "meeting-ai-test",
                        42,
                        MEETING_ID,
                        "SES-1",
                        false,
                        "preview"));

        String body = """
                {
                  "meeting_id": "22222222-2222-4222-8222-222222222222",
                  "session_id": "SES-1",
                  "transcript": "Merhaba. Direct STT gateway uzerinden calisacak.",
                  "segments": [{"text":"Merhaba.","start":0.0,"end":1.2}]
                }
                """;

        mockMvc.perform(post("/api/v1/admin/meetings/{meetingId}/intelligence/analyze", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.schema_version").value("5-adr0043"))
                .andExpect(jsonPath("$.meetingId").value(MEETING_ID.toString()))
                .andExpect(jsonPath("$.sessionId").value("SES-1"))
                .andExpect(jsonPath("$.action_items[0].text").value("Gateway route kaniti ekle."));

        verify(meetingIntelligenceService).analyze(eq(tenant), eq(MEETING_ID), any());
    }

    @Test
    void analyze_blankTranscript_returns400Validation() throws Exception {
        tenant();

        mockMvc.perform(post("/api/v1/admin/meetings/{meetingId}/intelligence/analyze", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"session_id":"SES-1","transcript":""}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    private AdminTenantContext tenant() {
        AdminTenantContext ctx = new AdminTenantContext(TENANT_ID, "admin@example.com", "admin@example.com");
        when(tenantContextResolver.resolveRequired()).thenReturn(ctx);
        return ctx;
    }
}

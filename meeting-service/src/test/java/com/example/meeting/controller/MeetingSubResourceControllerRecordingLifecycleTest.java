package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.meeting.config.SecurityConfigLocal;
import com.example.meeting.dto.v1.admin.RecordingLifecycleResponse;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.model.TranscriptStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeetingSubResourceController.class)
@ActiveProfiles("local")
@Import(SecurityConfigLocal.class)
class MeetingSubResourceControllerRecordingLifecycleTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingService meetingService;
    @MockitoBean private TenantContextResolver tenantContextResolver;

    @Test
    void syncRecordingLifecycleReturnsCanonicalProjection() throws Exception {
        AdminTenantContext tenant = tenant();
        when(meetingService.syncRecordingLifecycle(eq(tenant), eq(MEETING_ID), any()))
                .thenReturn(new RecordingLifecycleResponse(
                        MEETING_ID, SESSION_ID, "SES-1", MeetingStatus.COMPLETED,
                        TranscriptStatus.PROCESSING,
                        Instant.parse("2026-07-17T08:43:20Z"),
                        Instant.parse("2026-07-17T08:44:20Z")));

        mockMvc.perform(put("/api/v1/admin/meetings/{meetingId}/recording-lifecycle", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSessionId":"SES-1",
                                 "startedAt":"2026-07-17T08:43:20Z",
                                 "endedAt":"2026-07-17T08:44:20Z"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetingId").value(MEETING_ID.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()))
                .andExpect(jsonPath("$.meetingStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.transcriptStatus").value("PROCESSING"));

        verify(meetingService).syncRecordingLifecycle(eq(tenant), eq(MEETING_ID), any());
    }

    @Test
    void syncRecordingLifecycleRejectsInvalidIdentityAndReverseTime() throws Exception {
        tenant();

        mockMvc.perform(put("/api/v1/admin/meetings/{meetingId}/recording-lifecycle", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSessionId":"../foreign",
                                 "startedAt":"2026-07-17T08:44:20Z",
                                 "endedAt":"2026-07-17T08:43:20Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    @Test
    void syncRecordingLifecycleRejectsMissingStartWithoutValidatorFailure() throws Exception {
        tenant();

        mockMvc.perform(put("/api/v1/admin/meetings/{meetingId}/recording-lifecycle", MEETING_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalSessionId":"SES-1",
                                 "endedAt":"2026-07-17T08:44:20Z"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
    }

    private AdminTenantContext tenant() {
        AdminTenantContext value = new AdminTenantContext(TENANT_ID, "stable-user", "stable-user");
        when(tenantContextResolver.resolveRequired()).thenReturn(value);
        return value;
    }
}

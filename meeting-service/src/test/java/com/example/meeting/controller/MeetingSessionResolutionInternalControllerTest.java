package com.example.meeting.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.dto.v1.internal.MeetingSessionResolutionResponse;
import com.example.meeting.exception.GlobalExceptionHandler;
import com.example.meeting.service.MeetingService;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(controllers = MeetingSessionResolutionInternalController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MeetingSessionResolutionInternalControllerTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final String PATH = "/api/v1/internal/meetings/{id}/sessions/resolve";
    private static final String RESOLVE = "SVC_meeting:session:resolve";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingService meetingService;
    @MockitoBean private OpenFgaAuthzService authzService;

    @Test
    void resolverAuthorityReturnsCanonicalThinProjection() throws Exception {
        when(meetingService.resolveSession(TENANT, MEETING, "SES-42"))
                .thenReturn(new MeetingSessionResolutionResponse(
                        TENANT, TENANT, MEETING, SESSION, "SES-42"));

        mockMvc.perform(post(PATH, MEETING)
                        .with(jwt().authorities(new SimpleGrantedAuthority(RESOLVE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value(TENANT.toString()))
                .andExpect(jsonPath("$.meetingId").value(MEETING.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION.toString()))
                .andExpect(jsonPath("$.externalSessionId").value("SES-42"));
    }

    @Test
    void analysisWriterAuthorityCannotResolveSession() throws Exception {
        mockMvc.perform(post(PATH, MEETING)
                        .with(jwt().authorities(new SimpleGrantedAuthority(
                                "SVC_meeting:analysis-result:write")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(meetingService);
    }

    @Test
    void adminUserAuthorityCannotResolveSession() throws Exception {
        mockMvc.perform(post(PATH, MEETING)
                        .with(jwt().authorities(new SimpleGrantedAuthority("ROLE_ADMIN")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(meetingService);
    }

    @Test
    void malformedSourceIdentityIsRejectedBeforeServiceCall() throws Exception {
        mockMvc.perform(post(PATH, MEETING)
                        .with(jwt().authorities(new SimpleGrantedAuthority(RESOLVE)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantId\":\"" + TENANT + "\",\"externalSessionId\":\"bad value\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(meetingService);
    }

    private String body() {
        return "{\"tenantId\":\"" + TENANT + "\",\"externalSessionId\":\"SES-42\"}";
    }
}

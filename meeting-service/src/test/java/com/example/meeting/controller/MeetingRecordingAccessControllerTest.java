package com.example.meeting.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import com.example.meeting.dto.MeetingRecordingAccessResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.server.ResponseStatusException;

@WebMvcTest(MeetingRecordingAccessController.class)
@ActiveProfiles("test")
@Import(SecurityConfig.class)
class MeetingRecordingAccessControllerTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ORG_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final AdminTenantContext TENANT =
            new AdminTenantContext(TENANT_ID, "user-3", "user-3");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingService meetingService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @BeforeEach
    void setUp() {
        when(tenantContextResolver.resolveRequired()).thenReturn(TENANT);
        when(meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .thenReturn(new MeetingRecordingAccessResponse(MEETING_ID, TENANT_ID, ORG_ID));
    }

    @Test
    void nonAdminUserTokenCanUseRecordingAccessPreflight() throws Exception {
        mockMvc.perform(get("/api/v1/meetings/{id}/recording-access", MEETING_ID)
                        .with(nonAdminUserJwt()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meetingId").value(MEETING_ID.toString()))
                .andExpect(jsonPath("$.tenantId").value(TENANT_ID.toString()))
                .andExpect(jsonPath("$.orgId").value(ORG_ID.toString()))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.transcript").doesNotExist());

        verify(meetingService).requireRecordingAccess(TENANT, MEETING_ID);
    }

    @Test
    void recordingAccessDeniedReturns403() throws Exception {
        when(meetingService.requireRecordingAccess(TENANT, MEETING_ID))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Meeting recording access denied."));

        mockMvc.perform(get("/api/v1/meetings/{id}/recording-access", MEETING_ID)
                        .with(nonAdminUserJwt()))
                .andExpect(status().isForbidden());
    }

    @Test
    void unauthenticatedRecordingAccessPreflightReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/meetings/{id}/recording-access", MEETING_ID))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(meetingService);
    }

    private static RequestPostProcessor nonAdminUserJwt() {
        return jwt().jwt(j -> j.subject("user-3"))
                .authorities(
                        new SimpleGrantedAuthority("SCOPE_openid"),
                        new SimpleGrantedAuthority("SCOPE_email"),
                        new SimpleGrantedAuthority("SCOPE_profile"));
    }
}

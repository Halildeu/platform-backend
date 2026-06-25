package com.example.meeting.controller;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
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
    }

    @Test
    void nonAdminUserTokenCanUseRecordingAccessPreflight() throws Exception {
        mockMvc.perform(get("/api/v1/meetings/{id}/recording-access", MEETING_ID)
                        .with(nonAdminUserJwt()))
                .andExpect(status().isNoContent());

        verify(meetingService).requireRecordingAccess(TENANT, MEETING_ID);
    }

    @Test
    void recordingAccessDeniedReturns403() throws Exception {
        doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, "Meeting recording access denied."))
                .when(meetingService).requireRecordingAccess(TENANT, MEETING_ID);

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

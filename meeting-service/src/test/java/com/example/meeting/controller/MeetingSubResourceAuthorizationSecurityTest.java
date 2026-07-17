package com.example.meeting.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingWebMvcConfig;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

@WebMvcTest(MeetingSubResourceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class})
class MeetingSubResourceAuthorizationSecurityTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SUBJECT = "recorder-user";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingService meetingService;
    @MockitoBean private TenantContextResolver tenantContextResolver;
    @MockitoBean private OpenFgaAuthzService authzService;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, SUBJECT, SUBJECT));
    }

    @Test
    void nonAdminUserCannotReachRecordingLifecycle() throws Exception {
        mockMvc.perform(recordingLifecycle(nonAdminUserJwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, authzService);
    }

    @Test
    void adminScopeWithoutModuleManagerCannotReachRecordingLifecycle() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(recordingLifecycle(adminScopeJwt()))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder recordingLifecycle(
            RequestPostProcessor authentication) {
        return put("/api/v1/admin/meetings/{meetingId}/recording-lifecycle", MEETING_ID)
                .with(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"externalSessionId":"SES-security-test",
                         "startedAt":"2026-07-17T08:43:20Z"}
                        """);
    }

    private static RequestPostProcessor nonAdminUserJwt() {
        return jwt().jwt(j -> j.subject(SUBJECT))
                .authorities(new SimpleGrantedAuthority("SCOPE_openid"));
    }

    private static RequestPostProcessor adminScopeJwt() {
        return jwt().jwt(j -> j.subject(SUBJECT))
                .authorities(new SimpleGrantedAuthority("SCOPE_meeting"));
    }
}

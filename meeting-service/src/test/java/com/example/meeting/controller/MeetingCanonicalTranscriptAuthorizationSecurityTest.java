package com.example.meeting.controller;

import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingWebMvcConfig;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingCanonicalTranscriptService;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MeetingCanonicalTranscriptController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class})
class MeetingCanonicalTranscriptAuthorizationSecurityTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final String SUBJECT = "stable-sub";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingCanonicalTranscriptService service;
    @MockitoBean private TenantContextResolver tenantContextResolver;
    @MockitoBean private OpenFgaAuthzService authzService;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, SUBJECT, SUBJECT));
    }

    @Test
    void userWithoutMeetingScopeCannotReachTranscriptService() throws Exception {
        mockMvc.perform(get(path(), MEETING, RUN)
                        .with(jwt().jwt(token -> token.subject(SUBJECT))
                                .authorities(new SimpleGrantedAuthority("SCOPE_openid"))))
                .andExpect(status().isForbidden());

        verifyNoInteractions(service, authzService);
    }

    @Test
    void meetingScopeStillRequiresModuleViewerGate() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE))
                .thenReturn(false);
        mockMvc.perform(get(path(), MEETING, RUN)
                        .with(jwt().jwt(token -> token.subject(SUBJECT))
                                .authorities(new SimpleGrantedAuthority("SCOPE_meeting"))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    private static String path() {
        return "/api/v1/admin/meetings/{meetingId}/intelligence/results/"
                + "{analysisRunId}/transcript";
    }
}

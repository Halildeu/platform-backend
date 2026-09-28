package com.example.meeting.controller;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingWebMvcConfig;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.dto.v1.admin.MeetingSessionProcessingStatusResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingSessionProcessingStatusService;
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

@WebMvcTest(MeetingSessionProcessingStatusController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class})
class MeetingSessionProcessingStatusAuthorizationSecurityTest {
    private static final UUID TENANT = UUID.randomUUID(), MEETING = UUID.randomUUID(), SESSION = UUID.randomUUID();
    private static final String SUBJECT = "stable-sub";
    private static final String PATH = "/api/v1/admin/meetings/{meetingId}/sessions/{sessionId}/processing-status";
    private static final AdminTenantContext CONTEXT = new AdminTenantContext(TENANT, SUBJECT, SUBJECT);
    @Autowired private MockMvc mvc;
    @MockitoBean private MeetingSessionProcessingStatusService service;
    @MockitoBean private TenantContextResolver tenants;
    @MockitoBean private OpenFgaAuthzService authz;

    @BeforeEach
    void setup() {
        when(authz.isEnabled()).thenReturn(true);
        when(tenants.resolveRequired()).thenReturn(CONTEXT);
    }

    @Test
    void anonymousRequestIsDenied() throws Exception {
        mvc.perform(get(PATH, MEETING, SESSION)).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void meetingScopeIsRequiredBeforeModuleAndServiceChecks() throws Exception {
        mvc.perform(get(PATH, MEETING, SESSION).with(jwt().jwt(token -> token.subject(SUBJECT))
                .authorities(new SimpleGrantedAuthority("SCOPE_openid")))).andExpect(status().isForbidden());
        verifyNoInteractions(service, authz);
    }

    @Test
    void moduleViewerGateIsRequired() throws Exception {
        when(authz.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE)).thenReturn(false);
        mvc.perform(get(PATH, MEETING, SESSION).with(jwt().jwt(token -> token.subject(SUBJECT))
                .authorities(new SimpleGrantedAuthority("SCOPE_meeting")))).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test
    void successfulResponseIsScopedAndNeverCached() throws Exception {
        when(authz.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE)).thenReturn(true);
        when(service.read(CONTEXT, MEETING, SESSION))
                .thenReturn(new MeetingSessionProcessingStatusResponse(MEETING, SESSION, null, null));
        mvc.perform(get(PATH, MEETING, SESSION).with(jwt().jwt(token -> token.subject(SUBJECT))
                .authorities(new SimpleGrantedAuthority("SCOPE_meeting"))))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(header().string("Pragma", "no-cache"))
                .andExpect(jsonPath("$.meetingId").value(MEETING.toString()))
                .andExpect(jsonPath("$.sessionId").value(SESSION.toString()));
    }
}

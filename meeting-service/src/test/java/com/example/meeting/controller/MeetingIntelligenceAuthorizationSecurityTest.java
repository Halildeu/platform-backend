package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingWebMvcConfig;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.dto.v1.admin.MeetingIntelligenceAnalyzeResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingIntelligenceService;
import java.util.List;
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

@WebMvcTest(MeetingIntelligenceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class})
class MeetingIntelligenceAuthorizationSecurityTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SUBJECT = "user-3";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingIntelligenceService meetingIntelligenceService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com", "admin@example.com"));
    }

    @Test
    void nonAdminUserTokenCannotAnalyzeMeeting() throws Exception {
        mockMvc.perform(post("/api/v1/admin/meetings/{meetingId}/intelligence/analyze", MEETING_ID)
                        .with(nonAdminUserJwt(SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"SES-1\",\"transcript\":\"Merhaba\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingIntelligenceService, authzService);
    }

    @Test
    void adminScopeTokenCanAnalyzeWhenModuleGateAllows() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE))
                .thenReturn(true);
        when(meetingIntelligenceService.analyze(any(AdminTenantContext.class), any(), any()))
                .thenReturn(new MeetingIntelligenceAnalyzeResponse(
                        "5-adr0043", "verified_only", "Ozet", "verified",
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        0, true, 0, "mock", "meeting-ai-test", 12,
                        MEETING_ID, "SES-1", false, "preview"));

        mockMvc.perform(post("/api/v1/admin/meetings/{meetingId}/intelligence/analyze", MEETING_ID)
                        .with(adminScopeJwt(SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"session_id\":\"SES-1\",\"transcript\":\"Merhaba\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("Ozet"));
    }

    private static RequestPostProcessor nonAdminUserJwt(String subject) {
        return jwt().jwt(j -> j.subject(subject))
                .authorities(
                        new SimpleGrantedAuthority("SCOPE_openid"),
                        new SimpleGrantedAuthority("SCOPE_email"),
                        new SimpleGrantedAuthority("SCOPE_profile"));
    }

    private static RequestPostProcessor adminScopeJwt(String subject) {
        return jwt().jwt(j -> j.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_meeting"));
    }
}

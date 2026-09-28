package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingWebMvcConfig;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.dto.v1.admin.AssigneeCandidateResponse;
import com.example.meeting.dto.v1.admin.AssigneeCandidateSearchRequest;
import com.example.meeting.dto.v1.admin.AssigneeCandidateSearchResponse;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.ActionAssigneeNames;
import com.example.meeting.service.MeetingService;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Faz 24 (gitops#3834) — the people picker is gated exactly like creating an action: whoever may
 * assign a task on this meeting (module:meeting MANAGER) may look up whom to assign it to; nobody
 * else reaches the directory.
 */
@WebMvcTest(MeetingSubResourceController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class})
class MeetingAssigneeCandidatesSecurityTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String SUBJECT = "kc-requester";

    @Autowired private MockMvc mockMvc;
    @MockitoBean private MeetingService meetingService;
    @MockitoBean private TenantContextResolver tenantContextResolver;
    // gitops#3834: the controller names assignees after the service returns; unused here.
    @MockitoBean private ActionAssigneeNames assigneeNames;
    @MockitoBean private OpenFgaAuthzService authzService;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, SUBJECT, SUBJECT));
    }

    @Test
    void moduleManagerGetsTheCandidates() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE)).thenReturn(true);
        when(meetingService.searchAssigneeCandidates(any(AdminTenantContext.class), eq(MEETING_ID),
                any(AssigneeCandidateSearchRequest.class)))
                .thenReturn(new AssigneeCandidateSearchResponse(
                        List.of(new AssigneeCandidateResponse(7L, "Sevil Kaya", "sevil.kaya@acik.com"))));

        mockMvc.perform(search(meetingScopeJwt(), "{\"query\":\"sevil\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].userId").value(7))
                .andExpect(jsonPath("$.items[0].name").value("Sevil Kaya"))
                .andExpect(jsonPath("$.items[0].email").value("sevil.kaya@acik.com"));

        verify(meetingService).searchAssigneeCandidates(any(AdminTenantContext.class), eq(MEETING_ID),
                eq(new AssigneeCandidateSearchRequest("sevil", null)));
    }

    @Test
    void viewerWithoutManagerCannotSearch() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE)).thenReturn(false);

        mockMvc.perform(search(meetingScopeJwt(), "{\"query\":\"sevil\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService);
    }

    @Test
    void tokenWithoutMeetingScopeCannotSearch() throws Exception {
        mockMvc.perform(search(openIdOnlyJwt(), "{\"query\":\"sevil\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, authzService);
    }

    @Test
    void invalidBodiesAre400BeforeTheDirectory() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE)).thenReturn(true);

        mockMvc.perform(search(meetingScopeJwt(), "{\"query\":\"s\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(search(meetingScopeJwt(), "{\"query\":\"sevil\",\"limit\":21}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(search(meetingScopeJwt(), "{}")).andExpect(status().isBadRequest());

        verifyNoInteractions(meetingService);
    }

    private MockHttpServletRequestBuilder search(RequestPostProcessor authentication, String body) {
        return post("/api/v1/admin/meetings/{meetingId}/assignee-candidates/search", MEETING_ID)
                .with(authentication)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private static RequestPostProcessor openIdOnlyJwt() {
        return jwt().jwt(j -> j.subject(SUBJECT)).authorities(new SimpleGrantedAuthority("SCOPE_openid"));
    }

    private static RequestPostProcessor meetingScopeJwt() {
        return jwt().jwt(j -> j.subject(SUBJECT)).authorities(new SimpleGrantedAuthority("SCOPE_meeting"));
    }
}

package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.config.MeetingWebMvcConfig;
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.dto.v1.admin.MeetingResponse;
import com.example.meeting.dto.v1.admin.MeetingSearchCriteria;
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import com.example.meeting.service.MeetingHistorySearchMetrics;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.data.domain.Page;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Authorization-layer (real {@link SecurityConfig} admin chain) test for the
 * Faz 24 recorder PR-3 admin-surface re-close.
 *
 * <p>Recorder access now goes through the non-admin
 * {@code /api/v1/meetings/{id}/recording-access} endpoint with object-level
 * {@code meeting:{id}#can_record}. The temporary B-narrow relaxation on
 * {@code GET /api/v1/admin/meetings/{id}} is closed here: normal recorder USER
 * tokens must not reach the admin controller or module OpenFGA interceptor.
 *
 * <p>Uses {@code @ActiveProfiles("test")} so the real {@code !local & !dev}
 * {@link SecurityConfig} + {@link MeetingWebMvcConfig} interceptor are active
 * (mirrors {@code endpoint-admin-service} AdminEndpointAuthorizationSecurityTest).
 */
@WebMvcTest(MeetingAdminController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, MeetingWebMvcConfig.class})
class MeetingAdminAuthorizationSecurityTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String SUBJECT = "user-3";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingService meetingService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @MockitoBean
    private MeetingHistorySearchMetrics searchMetrics;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com", "admin@example.com"));
    }

    // ----- GET /{id}: admin-gated again after recorder-access preflight -----

    @Test
    void nonAdminUserTokenCannotGetMeetingById() throws Exception {
        mockMvc.perform(get("/api/v1/admin/meetings/{id}", MEETING_ID).with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, authzService);
    }

    // ----- Everything else under /api/v1/admin/** stays admin-gated at Spring -----

    @Test
    void nonAdminUserTokenCannotListMeetings() throws Exception {
        mockMvc.perform(get("/api/v1/admin/meetings").with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, authzService);
    }

    @Test
    void meetingViewerOpenFgaDenyBlocksSearchBeforeTenantQuery() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/meetings")
                        .param("title", "roadmap")
                        .with(adminScopeJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, searchMetrics);
    }

    @Test
    void meetingViewerOpenFgaAllowReachesTenantScopedSearch() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE))
                .thenReturn(true);
        when(meetingService.listMeetings(
                any(AdminTenantContext.class), any(MeetingSearchCriteria.class), any()))
                .thenReturn(Page.empty());

        mockMvc.perform(get("/api/v1/admin/meetings")
                        .param("title", "roadmap")
                        .with(adminScopeJwt(SUBJECT)))
                .andExpect(status().isOk());

        org.mockito.Mockito.verify(meetingService).listMeetings(
                any(AdminTenantContext.class), any(MeetingSearchCriteria.class), any());
    }

    @Test
    void nonAdminUserTokenCannotCreateMeeting() throws Exception {
        mockMvc.perform(post("/api/v1/admin/meetings")
                        .with(nonAdminUserJwt(SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"scheduledStart\":\"2026-06-16T09:00:00Z\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, authzService);
    }

    @Test
    void nonAdminUserTokenCannotDeleteMeetingById() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/meetings/{id}", MEETING_ID).with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, authzService);
    }

    @Test
    void nonAdminUserTokenCannotReadMeetingSubResources() throws Exception {
        // Sub-resource routes (MeetingSubResourceController:
        // /api/v1/admin/meetings/{id}/sessions|actions|decisions) are TWO segments under
        // /meetings/, so the single-segment GET matcher must NOT open them. Only
        // MeetingAdminController is loaded here on purpose: if the matcher wrongly opened
        // this path, Spring would allow it through to handler mapping and the assertion
        // would see 404 (no handler) instead of the expected 403 (Spring authorization).
        mockMvc.perform(get("/api/v1/admin/meetings/{id}/sessions", MEETING_ID).with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService, authzService);
    }

    // ----- Sanity: admin authority still reaches the write path -----

    @Test
    void adminScopeTokenCanCreateMeeting() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.MANAGER, "module", MeetingAuthz.MODULE))
                .thenReturn(true);
        when(meetingService.createMeeting(any(AdminTenantContext.class), any()))
                .thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/admin/meetings")
                        .with(adminScopeJwt(SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"weekly sync\",\"scheduledStart\":\"2026-06-16T09:00:00Z\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(MEETING_ID.toString()));
    }

    // ----- helpers -----

    private static RequestPostProcessor nonAdminUserJwt(String subject) {
        // Realistic recorder token authorities: scope=openid email profile, no admin role/scope.
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

    private MeetingResponse sampleResponse() {
        return new MeetingResponse(
                MEETING_ID, TENANT_ID, "weekly sync", "desc",
                MeetingStatus.SCHEDULED,
                Instant.parse("2026-06-16T09:00:00Z"),
                Instant.parse("2026-06-16T09:00:00Z"),
                Instant.parse("2026-06-16T10:00:00Z"),
                "organizer@example.com", "admin@example.com",
                Instant.parse("2026-06-16T08:00:00Z"),
                "admin@example.com",
                Instant.parse("2026-06-16T08:00:00Z"),
                0L);
    }
}

package com.example.meeting.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import com.example.meeting.model.MeetingStatus;
import com.example.meeting.security.AdminTenantContext;
import com.example.meeting.security.MeetingAuthz;
import com.example.meeting.security.TenantContextResolver;
import com.example.meeting.service.MeetingService;
import java.time.Instant;
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

/**
 * Authorization-layer (real {@link SecurityConfig} admin chain) test for the
 * Faz 24 recorder-consent B-narrow change (Codex 019eff98 AGREE).
 *
 * <p>The audio-gateway recorder-access check forwards a normal meeting-owner
 * USER token (only {@code SCOPE_openid/email/profile}, no admin role/scope) to
 * {@code GET /api/v1/admin/meetings/{id}}. Before B-narrow that token was
 * rejected at the Spring {@code hasAnyAuthority(...)} gate (403) before the
 * {@code @RequireModule} OpenFGA interceptor ever ran, so a recorder user could
 * never pass. B-narrow opens ONLY the single-segment GET-by-id to any
 * authenticated principal and lets the {@code @RequireModule(MEETING,can_view)}
 * OpenFGA gate + tenant/org predicate be the authorization; list,
 * sub-resources, and all mutations stay admin-gated.
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

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT_ID, "admin@example.com", "admin@example.com"));
    }

    // ----- GET /{id}: opened by B-narrow, now gated only by @RequireModule OpenFGA -----

    @Test
    void nonAdminUserTokenReachesGetByIdWhenOpenFgaAllows() throws Exception {
        when(authzService.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE))
                .thenReturn(true);
        when(meetingService.getMeeting(any(AdminTenantContext.class), eq(MEETING_ID)))
                .thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/admin/meetings/{id}", MEETING_ID).with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(MEETING_ID.toString()));
    }

    @Test
    void nonAdminUserTokenGetByIdStillDeniedByOpenFgaWhenNotPermitted() throws Exception {
        // Spring now lets the non-admin token through; the OpenFGA module gate denies → 403.
        when(authzService.check(SUBJECT, MeetingAuthz.VIEWER, "module", MeetingAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/meetings/{id}", MEETING_ID).with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService);
    }

    // ----- Everything else under /api/v1/admin/** stays admin-gated at Spring -----

    @Test
    void nonAdminUserTokenCannotListMeetings() throws Exception {
        mockMvc.perform(get("/api/v1/admin/meetings").with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService);
    }

    @Test
    void nonAdminUserTokenCannotCreateMeeting() throws Exception {
        mockMvc.perform(post("/api/v1/admin/meetings")
                        .with(nonAdminUserJwt(SUBJECT))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"x\",\"scheduledStart\":\"2026-06-16T09:00:00Z\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService);
    }

    @Test
    void nonAdminUserTokenCannotDeleteMeetingById() throws Exception {
        mockMvc.perform(delete("/api/v1/admin/meetings/{id}", MEETING_ID).with(nonAdminUserJwt(SUBJECT)))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService);
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

        verifyNoInteractions(meetingService);
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
                Instant.parse("2026-06-16T10:00:00Z"),
                "organizer@example.com", "admin@example.com",
                Instant.parse("2026-06-16T08:00:00Z"),
                "admin@example.com",
                Instant.parse("2026-06-16T08:00:00Z"),
                0L);
    }
}

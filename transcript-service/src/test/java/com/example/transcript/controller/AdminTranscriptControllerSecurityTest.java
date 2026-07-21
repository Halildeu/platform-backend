package com.example.transcript.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.transcript.config.SecurityConfig;
import com.example.transcript.config.TranscriptWebMvcConfig;
import com.example.transcript.dto.TranscriptSegmentDto;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.TenantContextResolver;
import com.example.transcript.security.TranscriptAuthz;
import com.example.transcript.service.TranscriptExportService;
import com.example.transcript.service.TranscriptSegmentService;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * Secured-tier proof for {@link AdminTranscriptController}: the
 * {@code @RequireModule} OpenFGA interceptor must
 * <ul>
 *   <li>403 + never invoke the service when the caller lacks {@code can_view}
 *       (fail-closed),</li>
 *   <li>200 when a viewer holds {@code can_view} on a read route,</li>
 *   <li>403 on a MANAGER (write) route when the caller only holds
 *       {@code can_view} (the method-level {@code @RequireModule} wins).</li>
 * </ul>
 *
 * <p>Mirrors endpoint-admin's {@code AdminEndpointComplianceGapSecurityTest}
 * (JWT/scope + OpenFGA mock shape).
 */
@WebMvcTest(controllers = AdminTranscriptController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, TranscriptWebMvcConfig.class})
class AdminTranscriptControllerSecurityTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SEGMENT = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID MEETING = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptSegmentService segmentService;

    @MockitoBean
    private TranscriptExportService exportService;

    @MockitoBean
    private TenantContextResolver tenantContextResolver;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @BeforeEach
    void setUp() {
        when(authzService.isEnabled()).thenReturn(true);
        when(tenantContextResolver.resolveRequired())
                .thenReturn(new AdminTenantContext(TENANT, "admin@example.com", "admin@example.com"));
    }

    @Test
    void deniedViewer_cannotReadSegment_403_andServiceNotInvoked() throws Exception {
        when(authzService.check("user-1", TranscriptAuthz.VIEWER, "module", TranscriptAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/transcripts/{id}", SEGMENT).with(adminJwt("user-1")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(segmentService);
    }

    @Test
    void viewer_canReadSegment_200() throws Exception {
        when(authzService.check("user-1", TranscriptAuthz.VIEWER, "module", TranscriptAuthz.MODULE))
                .thenReturn(true);
        when(segmentService.getSegment(any(), eq(SEGMENT))).thenReturn(dto());

        mockMvc.perform(get("/api/v1/admin/transcripts/{id}", SEGMENT).with(adminJwt("user-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(SEGMENT.toString()))
                .andExpect(jsonPath("$.meetingId").value(MEETING.toString()));
    }

    @Test
    void viewerWithoutManage_cannotCreate_403() throws Exception {
        // Caller is a viewer (can_view true) but lacks can_manage → the POST
        // (method-level @RequireModule MANAGER) must 403.
        when(authzService.check("user-1", TranscriptAuthz.VIEWER, "module", TranscriptAuthz.MODULE))
                .thenReturn(true);
        when(authzService.check("user-1", TranscriptAuthz.MANAGER, "module", TranscriptAuthz.MODULE))
                .thenReturn(false);

        String body = "{\"meetingId\":\"" + MEETING + "\",\"startTime\":0.0,\"endTime\":1.0}";
        mockMvc.perform(post("/api/v1/admin/transcripts")
                        .with(adminJwt("user-1"))
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());

        verifyNoInteractions(segmentService);
    }

    @Test
    void search_deniedViewer_403_andServiceNotInvoked() throws Exception {
        when(authzService.check("user-1", TranscriptAuthz.VIEWER, "module", TranscriptAuthz.MODULE))
                .thenReturn(false);

        mockMvc.perform(get("/api/v1/admin/transcripts/search")
                        .param("query", "budget")
                        .with(adminJwt("user-1")))
                .andExpect(status().isForbidden());

        verifyNoInteractions(segmentService);
    }

    private static TranscriptSegmentDto dto() {
        return new TranscriptSegmentDto(
                SEGMENT, TENANT, MEETING, null, null, 0.0, 1.0,
                "hello", null, 0.9, TranscriptSegmentStatus.DRAFT, 0L,
                Instant.parse("2026-06-16T10:00:00Z"), Instant.parse("2026-06-16T10:00:00Z"));
    }

    private static RequestPostProcessor adminJwt(String subject) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .jwt(jwt -> jwt.subject(subject))
                .authorities(new SimpleGrantedAuthority("SCOPE_transcript"));
    }
}

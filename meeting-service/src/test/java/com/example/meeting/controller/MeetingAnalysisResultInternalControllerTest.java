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
import com.example.meeting.config.SecurityConfig;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestResponse;
import com.example.meeting.exception.GlobalExceptionHandler;
import com.example.meeting.service.MeetingAnalysisResultIngestionService;
import java.time.Instant;
import java.util.UUID;
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
 * ai#244 BE-1c — internal analysis-result ingestion endpoint: security chain +
 * method-security + header/validation mapping, exercised against the REAL
 * controller (real {@link SecurityConfig} chains, MockMvc, mocked service).
 *
 * <p>Complements {@code MeetingInternalChainSecurityTest} (BE-1b, mock
 * controller): here the production controller's {@code @PreAuthorize},
 * {@code Idempotency-Key} header binding and {@code @Valid} body wiring are
 * proven, while the ingestion business logic (idempotency/conflict/atomicity)
 * lives in {@code MeetingAnalysisResultIngestionPostgresIntegrationTest}.
 */
@WebMvcTest(controllers = MeetingAnalysisResultInternalController.class)
@ActiveProfiles("test")
@Import({SecurityConfig.class, GlobalExceptionHandler.class})
class MeetingAnalysisResultInternalControllerTest {

    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID RUN_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final String PATH = "/api/v1/internal/meetings/{id}/analysis-results";
    private static final String SVC_WRITE = "SVC_meeting:analysis-result:write";

    private static final String VALID_BODY = """
            {
              "transcript_session_id": "SES-1",
              "transcript_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "analyzer_contract_version": "5-adr0043",
              "generated_at": "2026-07-11T10:00:00Z",
              "decisions": ["karar"],
              "action_items": []
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingAnalysisResultIngestionService ingestionService;

    // MeetingWebMvcConfig (a WebMvcConfigurer picked up by @WebMvcTest) requires this bean.
    @MockitoBean
    private OpenFgaAuthzService authzService;

    // ── Keycloak USER token (even admin) cannot reach the internal path ──────

    @Test
    void keycloakUserJwt_cannotIngest_403() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .with(adminUserJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ingestionService);
    }

    // ── Service token with the write authority reaches the controller (201) ──

    @Test
    void serviceToken_ingestsNewRun_201() throws Exception {
        when(ingestionService.ingest(eq(MEETING_ID), eq(RUN_ID), any()))
                .thenReturn(created());

        mockMvc.perform(post(PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .with(serviceJwt(SVC_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.persisted").value(true))
                .andExpect(jsonPath("$.storage_mode").value("persisted"))
                .andExpect(jsonPath("$.analysis_run_id").value(RUN_ID.toString()));

        verify(ingestionService).ingest(eq(MEETING_ID), eq(RUN_ID), any());
    }

    @Test
    void serviceToken_idempotentReplay_200() throws Exception {
        when(ingestionService.ingest(eq(MEETING_ID), eq(RUN_ID), any()))
                .thenReturn(replay());

        mockMvc.perform(post(PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .with(serviceJwt(SVC_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.idempotent_replay").value(true));
    }

    // ── Service token with the WRONG perm → 403 ──────────────────────────────

    @Test
    void serviceToken_wrongPerm_403() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .with(serviceJwt("SVC_meeting:other:read"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isForbidden());
        verifyNoInteractions(ingestionService);
    }

    // ── No credentials → 401 ─────────────────────────────────────────────────

    @Test
    void noAuth_401() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isUnauthorized());
    }

    // ── Missing Idempotency-Key header → 400 (not 500) ───────────────────────

    @Test
    void missingIdempotencyKeyHeader_400() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .with(serviceJwt(SVC_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("MISSING_HEADER"));
        verifyNoInteractions(ingestionService);
    }

    // ── Invalid UUID Idempotency-Key → 400 (not 500) ─────────────────────────

    @Test
    void invalidIdempotencyKeyHeader_400() throws Exception {
        mockMvc.perform(post(PATH, MEETING_ID)
                        .header("Idempotency-Key", "not-a-uuid")
                        .with(serviceJwt(SVC_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(VALID_BODY))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(ingestionService);
    }

    // ── Body validation (blank transcript_session_id) → 400 VALIDATION_ERROR ──

    @Test
    void invalidBody_400Validation() throws Exception {
        String invalid = """
                {
                  "transcript_session_id": "",
                  "transcript_sha256": "notahash",
                  "analyzer_contract_version": "5-adr0043",
                  "generated_at": "2026-07-11T10:00:00Z"
                }
                """;
        mockMvc.perform(post(PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .with(serviceJwt(SVC_WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalid))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("VALIDATION_ERROR"));
        verifyNoInteractions(ingestionService);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private static MeetingAnalysisResultIngestResponse created() {
        return MeetingAnalysisResultIngestResponse.persisted(
                RUN_ID, MEETING_ID, false, 1, 0, null, Instant.parse("2026-07-11T10:00:00Z"));
    }

    private static MeetingAnalysisResultIngestResponse replay() {
        return MeetingAnalysisResultIngestResponse.persisted(
                RUN_ID, MEETING_ID, true, 1, 0, null, Instant.parse("2026-07-11T10:00:00Z"));
    }

    private static RequestPostProcessor serviceJwt(String svcAuthority) {
        return jwt().jwt(j -> j.subject("meeting-ai-service").claim("iss", "auth-service"))
                .authorities(new SimpleGrantedAuthority(svcAuthority));
    }

    private static RequestPostProcessor adminUserJwt() {
        return jwt().jwt(j -> j.subject("admin-user"))
                .authorities(
                        new SimpleGrantedAuthority("ROLE_ADMIN"),
                        new SimpleGrantedAuthority("ROLE_MEETING_ADMIN"),
                        new SimpleGrantedAuthority("SCOPE_meeting"));
    }
}

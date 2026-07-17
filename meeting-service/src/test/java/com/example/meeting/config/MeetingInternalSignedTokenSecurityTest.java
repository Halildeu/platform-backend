package com.example.meeting.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.meeting.controller.MeetingAnalysisResultInternalController;
import com.example.meeting.controller.MeetingSessionResolutionInternalController;
import com.example.meeting.dto.v1.internal.MeetingAnalysisResultIngestResponse;
import com.example.meeting.dto.v1.internal.MeetingSessionResolutionResponse;
import com.example.meeting.exception.GlobalExceptionHandler;
import com.example.meeting.service.MeetingAnalysisResultIngestionService;
import com.example.meeting.service.MeetingService;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** End-to-end internal route authorization with real RS256 bearer tokens. */
@WebMvcTest(controllers = {
        MeetingAnalysisResultInternalController.class,
        MeetingSessionResolutionInternalController.class
})
@ActiveProfiles("test")
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        MeetingInternalSignedTokenSecurityTest.SignedTokenDecoderConfiguration.class
})
class MeetingInternalSignedTokenSecurityTest {

    private static final String ISSUER = "auth-service";
    private static final String AUDIENCE = "meeting-service";
    private static final String MEETING_AI = "meeting-ai";
    private static final String TRANSCRIPT_SERVICE = "transcript-service";
    private static final String WRITE = "meeting:analysis-result:write";
    private static final String RESOLVE = "meeting:session:resolve";

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID MEETING_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID SESSION_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID RUN_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final KeyPair SERVICE_KEYS = rsa();

    private static final String RESOLVE_PATH = "/api/v1/internal/meetings/{id}/sessions/resolve";
    private static final String WRITE_PATH = "/api/v1/internal/meetings/{id}/analysis-results";
    private static final String RESOLVE_BODY = "{\"tenantId\":\"" + TENANT_ID
            + "\",\"externalSessionId\":\"SES-42\"}";
    private static final String WRITE_BODY = """
            {
              "transcript_session_id": "SES-42",
              "transcript_sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
              "analyzer_contract_version": "5-adr0043",
              "generated_at": "2026-07-18T10:00:00Z",
              "decisions": [],
              "actions": []
            }
            """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MeetingService meetingService;

    @MockitoBean
    private MeetingAnalysisResultIngestionService ingestionService;

    @MockitoBean
    private OpenFgaAuthzService authzService;

    @Test
    void transcriptServiceSignedToken_resolvesSession() throws Exception {
        when(meetingService.resolveSession(TENANT_ID, MEETING_ID, "SES-42"))
                .thenReturn(new MeetingSessionResolutionResponse(
                        TENANT_ID, TENANT_ID, MEETING_ID, SESSION_ID, "SES-42"));

        mockMvc.perform(post(RESOLVE_PATH, MEETING_ID)
                        .header(AUTHORIZATION, bearer(TRANSCRIPT_SERVICE, RESOLVE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESOLVE_BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(SESSION_ID.toString()));
    }

    @Test
    void signedTokenFromClientOutsideAllowlist_isRejectedBeforeController() throws Exception {
        mockMvc.perform(post(RESOLVE_PATH, MEETING_ID)
                        .header(AUTHORIZATION, bearer("other-service", RESOLVE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESOLVE_BODY))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(meetingService);
    }

    @Test
    void transcriptResolvePermission_cannotWriteAnalysisResult() throws Exception {
        mockMvc.perform(post(WRITE_PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .header(AUTHORIZATION, bearer(TRANSCRIPT_SERVICE, RESOLVE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WRITE_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(ingestionService);
    }

    @Test
    void meetingAiWritePermission_cannotResolveSession() throws Exception {
        mockMvc.perform(post(RESOLVE_PATH, MEETING_ID)
                        .header(AUTHORIZATION, bearer(MEETING_AI, WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(RESOLVE_BODY))
                .andExpect(status().isForbidden());

        verifyNoInteractions(meetingService);
    }

    @Test
    void meetingAiSignedToken_stillWritesAnalysisResult() throws Exception {
        when(ingestionService.ingest(eq(MEETING_ID), eq(RUN_ID), any()))
                .thenReturn(MeetingAnalysisResultIngestResponse.persisted(
                        RUN_ID, MEETING_ID, false, 0, 0, null,
                        Instant.parse("2026-07-18T10:00:00Z")));

        mockMvc.perform(post(WRITE_PATH, MEETING_ID)
                        .header("Idempotency-Key", RUN_ID.toString())
                        .header(AUTHORIZATION, bearer(MEETING_AI, WRITE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WRITE_BODY))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.analysis_run_id").value(RUN_ID.toString()));
    }

    private static String bearer(String clientId, String permission) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .subject(clientId)
                .issuer(ISSUER)
                .audience(AUDIENCE)
                .claim("client_id", clientId)
                .claim("svc", clientId)
                .claim("perm", List.of(permission))
                .issueTime(new Date())
                .expirationTime(new Date(System.currentTimeMillis() + 300_000))
                .build();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-service-key").build(),
                claims);
        jwt.sign(new RSASSASigner(SERVICE_KEYS.getPrivate()));
        return "Bearer " + jwt.serialize();
    }

    private static KeyPair rsa() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (GeneralSecurityException ex) {
            throw new ExceptionInInitializerError(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SignedTokenDecoderConfiguration {

        @Bean
        @Primary
        JwtDecoder signedServiceTokenDecoder() {
            NimbusJwtDecoder decoder = NimbusJwtDecoder
                    .withPublicKey((RSAPublicKey) SERVICE_KEYS.getPublic())
                    .build();
            decoder.setJwtValidator(SecurityConfig.buildInternalServiceValidator(
                    ISSUER,
                    List.of(AUDIENCE),
                    List.of(MEETING_AI, TRANSCRIPT_SERVICE)));
            return decoder;
        }
    }
}

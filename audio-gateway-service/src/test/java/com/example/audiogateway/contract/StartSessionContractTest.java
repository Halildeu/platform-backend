package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Audio Gateway Contract v1.0 — POST /api/v1/audio-gateway/sessions.
 *
 * <p>ADR-0031 + Codex {@code 019e8c26} iter-2 AGREE PR-gw-01A scope:
 * <ul>
 *   <li>Path canonical {@code /api/v1/audio-gateway/sessions} (eski {@code /api/meeting-audio} removed)</li>
 *   <li>{@code Idempotency-Key} header zorunlu (16-128 char opaque token)</li>
 *   <li>Replay: same key + same signature → 200 OK (replay)</li>
 *   <li>Conflict: same key + different signature → 409 Conflict</li>
 *   <li>JWT fail-closed: missing/invalid 401, missing tenant/user claim 403</li>
 *   <li>Audio format/sample rate/channels guards (415/400)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class StartSessionContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_KEY_HEADER = "Idempotency-Key";
    private static final String VALID_KEY = "c0ffee0011223344-pr-gw-01a-test";

    @Autowired
    private WebTestClient client;

    private static StartSessionRequest validRequest() {
        return new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 16000, 1);
    }

    private WebTestClient withClaims() {
        return client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)));
    }

    @Test
    void noAuth_returns401() {
        client.post()
                .uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void missingIdempotencyKey_returns400() {
        withClaims()
                .post().uri(SESSIONS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_MISSING);
                    assertThat(err.retryable()).isFalse();
                });
    }

    @Test
    void shortIdempotencyKey_returns400_invalid() {
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, "tooshort")  // < 16 char
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_INVALID));
    }

    @Test
    void jwtMissingCompanyIdClaim_returns403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("userId", 42L)))
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN);
                    assertThat(err.message()).contains("companyId");
                });
    }

    @Test
    void jwtMissingUserIdClaim_returns403() {
        client.mutateWith(mockJwt().jwt(jwt -> jwt.claim("companyId", 1L)))
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.message()).contains("userId"));
    }

    @Test
    void unsupportedAudioFormat_returns415() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.MP3, 16000, 1);

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY + "-mp3")
                .bodyValue(req)
                .exchange()
                .expectStatus().isEqualTo(415)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    @Test
    void unsupportedSampleRate_returns400() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 22050, 1);

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY + "-rate")
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED);
                    assertThat(err.message()).contains("22050");
                });
    }

    @Test
    void stereoChannel_returns400() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 16000, 2);

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY + "-stereo")
                .bodyValue(req)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_FORMAT_REJECTED));
    }

    @Test
    void happyPath_returns201_withSessionAndUrls() {
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY + "-happy")
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).startsWith("SES-");
                    assertThat(resp.correlationId()).isNotNull();
                    assertThat(resp.statusUrl()).contains(resp.sessionId()).endsWith("/status");
                    assertThat(resp.finishUrl()).contains(resp.sessionId()).endsWith("/finish");
                    assertThat(resp.websocketUrl()).contains("/api/v1/audio-gateway/sessions/")
                            .endsWith("/stream");
                    assertThat(resp.chunkUploadUrl()).contains("/api/v1/audio-gateway/sessions/")
                            .endsWith("/chunks");
                    assertThat(resp.sessionStartMs()).isPositive();
                });
    }

    @Test
    void idempotencyReplay_sameKeyAndSignature_returns200_withSameSessionId() {
        final String key = VALID_KEY + "-replay";
        // 1st call → 201
        final String firstId = withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, key)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .returnResult().getResponseBody().sessionId();

        // 2nd call same key + same signature → 200 with same sessionId (replay)
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, key)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isOk()
                .expectBody(StartSessionResponse.class)
                .value(resp -> assertThat(resp.sessionId()).isEqualTo(firstId));
    }

    @Test
    void idempotencyConflict_sameKeyDifferentSignature_returns409() {
        final String key = VALID_KEY + "-conflict";
        // 1st: tr language
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, key)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated();

        // 2nd: same key but different deviceId signature → 409 conflict
        final StartSessionRequest mutated = new StartSessionRequest(
                "MTG-2026-0001", "device-OTHER", "tr", AudioFormat.WAV, 16000, 1);
        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, key)
                .bodyValue(mutated)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void correlationIdPropagation_echoesHeader() {
        final String corrId = "c0ffee01-1234-1234-1234-123456789012";

        withClaims()
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, VALID_KEY + "-corr")
                .header("X-Correlation-Id", corrId)
                .bodyValue(validRequest())
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", corrId);
    }
}

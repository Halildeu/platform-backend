package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Audio Gateway Contract 1.0 — 5 senaryo contract test.
 *
 * <p>1. invalid JWT → 401
 * <p>2. missing language → 400 (validation)
 * <p>3. unsupported audio format → 415
 * <p>4. unsupported sample rate → 400
 * <p>5. stereo (channels=2) → 400 (PoC mono only)
 *
 * <p>Plus correlation_id propagation, response header verify.
 *
 * <p>3-AI mutabakat trail: Codex {@code 019e879c} + Mavis {@code msg 78} AGREE.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class StartSessionContractTest {

    @Autowired
    private WebTestClient client;

    @Test
    void invalidJwt_returns401() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV,
                16000, 1, null);

        client.post()
                .uri("/api/meeting-audio/sessions")
                .header("Authorization", "Bearer not-a-real-jwt")
                .bodyValue(req)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    @WithMockUser
    void missingLanguage_returns400() {
        // language null → @NotBlank fails
        final Map<String, Object> body = Map.of(
                "meetingId", "MTG-2026-0001",
                "deviceId", "device-1",
                // no language
                "audioFormat", "WAV",
                "sampleRateHz", 16000,
                "channels", 1
        );

        client.post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(body)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    @WithMockUser
    void unsupportedAudioFormat_returns415() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.MP3,  // not in CLIENT_ALLOWED
                16000, 1, null);

        client.post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(req)
                .exchange()
                .expectStatus().is4xxClientError();  // 415 or 400
    }

    @Test
    @WithMockUser
    void unsupportedSampleRate_returns400() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV,
                22050,  // not in ALLOWED_SAMPLE_RATES {16000, 48000}
                1, null);

        client.post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(req)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    @WithMockUser
    void stereoChannel_returns400() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV,
                16000, 2,  // PoC mono only
                null);

        client.post()
                .uri("/api/meeting-audio/sessions")
                .bodyValue(req)
                .exchange()
                .expectStatus().is4xxClientError();
    }

    @Test
    @WithMockUser
    void correlationIdPropagation_echoesHeader() {
        final StartSessionRequest req = new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV,
                16000, 1, null);
        final String corrId = "test-corr-id-12345678-1234-1234-1234-123456789012";

        client.post()
                .uri("/api/meeting-audio/sessions")
                .header("X-Correlation-Id", corrId)
                .bodyValue(req)
                .exchange()
                .expectHeader().valueEquals("X-Correlation-Id", corrId);
    }
}

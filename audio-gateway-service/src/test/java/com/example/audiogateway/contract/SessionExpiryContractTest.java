package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * #428 (Codex reopen) — {@code audio.gateway.bounds.max-session-minutes} enforcement.
 *
 * <p>Isolated in its own {@code @SpringBootTest} context (own {@code max-session-minutes=0}
 * property override) rather than added to {@link ChunkAdmissionContractTest}, so the
 * default (60-minute) bound used by every other contract test is untouched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test",
        "audio.gateway.bounds.max-session-minutes=0"
})
class SessionExpiryContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_HEADER = "Idempotency-Key";

    @Autowired
    private WebTestClient client;

    private WebTestClient asUser(final long companyId, final long userId) {
        return client.mutateWith(mockJwt()
                .jwt(j -> j.claim("companyId", companyId).claim("userId", userId)));
    }

    @Test
    void chunkAfterMaxSessionMinutes_returns409SessionExpired() {
        final String sessionId = asUser(1L, 1L)
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, "fixture-428-session-expiry-1")
                .bodyValue(new StartSessionRequest(
                        "22222222-2222-4222-8222-222222222222", "device-1", "tr",
                        AudioFormat.WAV, 16000, 1))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .returnResult().getResponseBody().sessionId();

        // max-session-minutes=0 -> the session is expired at creation time; no
        // elapsed-time race or clock injection is needed.
        asUser(1L, 1L)
                .post().uri(SESSIONS_PATH + "/" + sessionId + "/chunks")
                .header(IDEMP_HEADER, "fixture-428-session-expiry-1-chunk0")
                .header("X-Audio-Chunk-Seq", "0")
                .header("X-Audio-Chunk-Started-At-Ms", "1781820000000")
                .header("X-Audio-Format", "WAV")
                .header("X-Audio-Sample-Rate-Hz", "16000")
                .header("X-Audio-Channels", "1")
                .header("X-Audio-Byte-Length", "4")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .bodyValue(new byte[] {1, 2, 3, 4})
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_SESSION_EXPIRED));
    }
}

package com.example.audiogateway.contract;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.FinishResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;
import com.example.audiogateway.dto.StatusResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * Audio Gateway Contract v1.0 — GET /status + POST /finish (lifecycle).
 *
 * <p>ADR-0031 + Codex {@code 019e8c26} iter-2 AGREE PR-gw-01A:
 * <ul>
 *   <li>{@code GET /sessions/{id}/status} — STARTED → FINISHED snapshot</li>
 *   <li>{@code POST /sessions/{id}/finish} — terminal lifecycle + idempotent (same key replay)</li>
 *   <li>Idempotency-Key header zorunlu on finish (Codex iter-2 AGREE)</li>
 *   <li>Unknown session → 404; owner mismatch → 403; conflict → 409</li>
 *   <li>chunkCount/lastChunkSeq always 0 in PR-gw-01A (chunk admission slice B/C/D)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class SessionLifecycleContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_KEY_HEADER = "Idempotency-Key";

    @Autowired
    private WebTestClient client;

    private static StartSessionRequest validRequest() {
        return new StartSessionRequest(
                "MTG-2026-0001", "device-1", "tr", AudioFormat.WAV, 16000, 1);
    }

    private WebTestClient asUser(final long companyId, final long userId) {
        return client.mutateWith(mockJwt()
                .jwt(j -> j.claim("companyId", companyId).claim("userId", userId)));
    }

    private String startFresh(final long companyId, final long userId, final String key) {
        return asUser(companyId, userId)
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_KEY_HEADER, key)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .returnResult().getResponseBody().sessionId();
    }

    // ---------------- Status ----------------

    @Test
    void status_unknownSession_returns404() {
        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/SES-does-not-exist/status")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_SESSION_NOT_FOUND));
    }

    @Test
    void status_otherTenant_returns404() {
        final String sid = startFresh(1L, 42L, "status-other-tenant-fixture-001");

        // tenant 2 → registry'de mevcut ama tenant filter ile NotFound semantik
        asUser(2L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void status_started_returnsStartedState() {
        final String sid = startFresh(1L, 42L, "status-started-fixture-key-abc");

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).isEqualTo(sid);
                    assertThat(resp.state()).isEqualTo("STARTED");
                    assertThat(resp.chunkCount()).isZero();   // PR-gw-01A
                    assertThat(resp.lastChunkSeq()).isZero(); // PR-gw-01A
                    assertThat(resp.sessionStartMs()).isPositive();
                });
    }

    // ---------------- Finish ----------------

    @Test
    void finish_missingIdempotencyKey_returns400() {
        final String sid = startFresh(1L, 42L, "finish-missing-key-fixture-001");

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_MISSING));
    }

    @Test
    void finish_unknownSession_returns404() {
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/SES-not-here/finish")
                .header(IDEMP_KEY_HEADER, "finish-unknown-fixture-001abc")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_SESSION_NOT_FOUND));
    }

    @Test
    void finish_ownerMismatch_returns403() {
        final String sid = startFresh(1L, 42L, "finish-owner-mismatch-fixture-1");

        asUser(99L, 88L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_KEY_HEADER, "finish-other-tenant-fixture-1")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN));
    }

    @Test
    void finish_happyPath_returns200_alreadyFinishedFalse() {
        final String sid = startFresh(1L, 42L, "finish-happy-fixture-key-aaa");
        final String finishKey = "finish-happy-key-fixture-aaa1";

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_KEY_HEADER, finishKey)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinishResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).isEqualTo(sid);
                    assertThat(resp.finalState()).isEqualTo("FINISHED");
                    assertThat(resp.alreadyFinished()).isFalse();
                    assertThat(resp.finishedAtMs()).isPositive();
                });
    }

    @Test
    void finish_idempotentReplay_sameKey_returns200_alreadyFinishedTrue() {
        final String sid = startFresh(1L, 42L, "finish-replay-fixture-key-bbb");
        final String finishKey = "finish-replay-key-fixture-bbb1";

        // 1st finish → FINISHED, alreadyFinished=false
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_KEY_HEADER, finishKey)
                .exchange()
                .expectStatus().isOk();

        // 2nd finish same key → replay alreadyFinished=true
        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_KEY_HEADER, finishKey)
                .exchange()
                .expectStatus().isOk()
                .expectBody(FinishResponse.class)
                .value(resp -> {
                    assertThat(resp.alreadyFinished()).isTrue();
                    assertThat(resp.finalState()).isEqualTo("FINISHED");
                });
    }

    @Test
    void finish_idempotencyConflict_differentKeyOnFinished_returns409() {
        final String sid = startFresh(1L, 42L, "finish-conflict-fixture-key-ccc");
        final String firstKey = "finish-conflict-key1-fixture-ccc";
        final String secondKey = "finish-conflict-key2-fixture-ccc";

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_KEY_HEADER, firstKey)
                .exchange()
                .expectStatus().isOk();

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_KEY_HEADER, secondKey)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_IDEMPOTENCY_CONFLICT));
    }

    @Test
    void status_afterFinish_returnsFinishedState() {
        final String sid = startFresh(1L, 42L, "status-after-finish-fixture-1");
        final String finishKey = "status-after-finish-key-fixt1";

        asUser(1L, 42L)
                .post().uri(SESSIONS_PATH + "/" + sid + "/finish")
                .header(IDEMP_KEY_HEADER, finishKey)
                .exchange()
                .expectStatus().isOk();

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/status")
                .exchange()
                .expectStatus().isOk()
                .expectBody(StatusResponse.class)
                .value(resp -> assertThat(resp.state()).isEqualTo("FINISHED"));
    }
}

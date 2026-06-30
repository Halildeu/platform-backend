package com.example.audiogateway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.example.audiogateway.dto.AudioFormat;
import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.StartSessionRequest;
import com.example.audiogateway.dto.StartSessionResponse;
import com.example.audiogateway.dto.TranscriptEventResponse;
import com.example.audiogateway.dto.TranscriptEventsResponse;
import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;
import com.example.audiogateway.service.DirectSttTranscriptEventReader;
import com.example.audiogateway.service.SessionRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class TranscriptEventsContractTest {

    private static final String SESSIONS_PATH = "/api/v1/audio-gateway/sessions";
    private static final String IDEMP_HEADER = "Idempotency-Key";
    private static final String MEETING_ID = "22222222-2222-4222-8222-222222222222";

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private DirectSttTranscriptEventReader transcriptEventReader;

    @MockitoBean
    private AudioGatewayAuditSink auditSink;

    private static StartSessionRequest validRequest() {
        return new StartSessionRequest(MEETING_ID, "desktop-1", "tr", AudioFormat.PCM16, 16000, 1);
    }

    private WebTestClient asUser(final long companyId, final long userId) {
        return client.mutateWith(mockJwt()
                .jwt(j -> j.claim("companyId", companyId).claim("userId", userId)));
    }

    private String startFresh() {
        return asUser(1L, 42L)
                .post().uri(SESSIONS_PATH)
                .header(IDEMP_HEADER, "transcript-events-" + UUID.randomUUID())
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StartSessionResponse.class)
                .returnResult()
                .getResponseBody()
                .sessionId();
    }

    @Test
    void transcriptEventsReturnsCursorPagedEventsForOwner() {
        final String sid = startFresh();
        when(transcriptEventReader.read(any(SessionRecord.class), isNull(), anyInt(), any()))
                .thenReturn(new TranscriptEventsResponse(
                        sid,
                        "corr-test",
                        List.of(new TranscriptEventResponse(
                                "1680000000000-0",
                                sid,
                                MEETING_ID,
                                3L,
                                1_250L,
                                "merhaba dunya",
                                13,
                                "DRAFT",
                                1_500L,
                                "tr",
                                1.2d,
                                "corr-stt")),
                        "1680000000000-0",
                        false));

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/transcript-events")
                .exchange()
                .expectStatus().isOk()
                .expectBody(TranscriptEventsResponse.class)
                .value(resp -> {
                    assertThat(resp.sessionId()).isEqualTo(sid);
                    assertThat(resp.events()).hasSize(1);
                    assertThat(resp.events().get(0).text()).isEqualTo("merhaba dunya");
                    assertThat(resp.nextCursor()).isEqualTo("1680000000000-0");
                });
        verify(auditSink).emit(isA(AuditEvent.TranscriptEventsAccessed.class));
    }

    @Test
    void transcriptEventsClampsClientLimitToConfiguredReadBatchSize() {
        final String sid = startFresh();
        when(transcriptEventReader.read(any(SessionRecord.class), isNull(), eq(50), any()))
                .thenReturn(new TranscriptEventsResponse(sid, "corr-test", List.of(), "0-0", false));

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/transcript-events?limit=10000")
                .exchange()
                .expectStatus().isOk();

        verify(transcriptEventReader).read(any(SessionRecord.class), isNull(), eq(50), any());
        verify(auditSink).emit(isA(AuditEvent.TranscriptEventsAccessed.class));
    }

    @Test
    void transcriptEventsOtherUserGets404WithoutInvokingReader() {
        final String sid = startFresh();

        asUser(1L, 99L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/transcript-events")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_SESSION_NOT_FOUND));

        verify(transcriptEventReader, never()).read(any(), any(), anyInt(), any());
    }

    @Test
    void transcriptEventsInvalidCursorReturns400() {
        final String sid = startFresh();

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/transcript-events?after=not-a-stream-id")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_VALIDATION));
    }

    @Test
    void streamTranscriptEventsInvalidLastEventIdReturns400() {
        final String sid = startFresh();

        asUser(1L, 42L)
                .get().uri(SESSIONS_PATH + "/" + sid + "/transcript-events/stream")
                .header("Last-Event-ID", "invalid")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_VALIDATION));
    }
}

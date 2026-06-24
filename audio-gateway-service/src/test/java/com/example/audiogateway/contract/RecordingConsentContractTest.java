package com.example.audiogateway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.RecordingConsentRequest;
import com.example.audiogateway.dto.RecordingConsentResponse;
import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;
import com.example.audiogateway.service.MeetingAccessValidator;
import com.example.audiogateway.service.MeetingAccessValidator.Decision;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * Audio Gateway Contract v1.0 — POST /api/v1/audio-gateway/consents.
 *
 * <p>Recorder consent is a legal/audit precondition. The gateway derives actor
 * identity from JWT, records server time, validates meeting visibility through
 * the same meeting-service boundary used by start-session, and fail-closes when
 * the audit sink cannot persist the proof event.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class RecordingConsentContractTest {

    private static final String CONSENTS_PATH = "/api/v1/audio-gateway/consents";
    private static final String MEETING_ID = "22222222-2222-4222-8222-222222222222";
    private static final String CAPTURE_ID = "33333333-3333-4333-8333-333333333333";
    private static final String CONSENT_HASH =
            "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Autowired
    private WebTestClient client;

    @Autowired
    private RecordingAudioGatewayAuditSink auditSink;

    @MockitoBean
    private MeetingAccessValidator meetingAccessValidator;

    @BeforeEach
    void resetFakes() {
        auditSink.reset();
        when(meetingAccessValidator.validate(any(), any(), any()))
                .thenReturn(Mono.just(Decision.granted()));
    }

    @TestConfiguration
    static class FakeBeans {
        @Bean
        @Primary
        public RecordingAudioGatewayAuditSink recordingAudioGatewayAuditSink() {
            return new RecordingAudioGatewayAuditSink();
        }
    }

    static class RecordingAudioGatewayAuditSink implements AudioGatewayAuditSink {
        private final List<AuditEvent> events = new CopyOnWriteArrayList<>();
        private final AtomicBoolean throwOnEmit = new AtomicBoolean(false);

        List<AuditEvent> events() {
            return List.copyOf(events);
        }

        void reset() {
            events.clear();
            throwOnEmit.set(false);
        }

        void throwOnEmit() {
            throwOnEmit.set(true);
        }

        @Override
        public void emit(final AuditEvent event) {
            if (throwOnEmit.get()) {
                throw new IllegalStateException("audit down");
            }
            events.add(event);
        }
    }

    private WebTestClient asUser(final long companyId, final long userId) {
        return client.mutateWith(mockJwt()
                .jwt(j -> j.subject("sub-990001").claim("companyId", companyId).claim("userId", userId)));
    }

    private static RecordingConsentRequest validRequest() {
        return new RecordingConsentRequest(
                MEETING_ID,
                CAPTURE_ID,
                "recorder-consent-v1",
                CONSENT_HASH,
                "tr-TR");
    }

    @Test
    void consentGranted_returns201_andEmitsServerTimeAuditEvent() {
        final long before = System.currentTimeMillis();

        asUser(1L, 990001L)
                .post().uri(CONSENTS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RecordingConsentResponse.class)
                .value(resp -> {
                    assertThat(resp.meetingId()).isEqualTo(MEETING_ID);
                    assertThat(resp.captureId()).isEqualTo(CAPTURE_ID);
                    assertThat(resp.consentTextHash()).isEqualTo(CONSENT_HASH);
                    assertThat(resp.locale()).isEqualTo("tr-TR");
                    assertThat(resp.correlationId()).isNotBlank();
                    assertThat(resp.acceptedAtMs()).isGreaterThanOrEqualTo(before);
                    assertThat(resp.acceptedAtMs()).isLessThanOrEqualTo(System.currentTimeMillis());
                });

        assertThat(auditSink.events()).hasSize(1);
        assertThat(auditSink.events().get(0))
                .isInstanceOfSatisfying(AuditEvent.RecordingConsentGranted.class, event -> {
                    assertThat(event.meetingId()).isEqualTo(MEETING_ID);
                    assertThat(event.captureId()).isEqualTo(CAPTURE_ID);
                    assertThat(event.tenantId()).isEqualTo(1L);
                    assertThat(event.userId()).isEqualTo(990001L);
                    assertThat(event.subjectId()).isEqualTo("sub-990001");
                    assertThat(event.consentVersion()).isEqualTo("recorder-consent-v1");
                    assertThat(event.consentTextHash()).isEqualTo(CONSENT_HASH);
                    assertThat(event.locale()).isEqualTo("tr-TR");
                    assertThat(event.acceptedAtMs()).isGreaterThanOrEqualTo(before);
                });
    }

    @Test
    void consentNoAuth_returns401_andDoesNotEmitAudit() {
        client.post()
                .uri(CONSENTS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isUnauthorized();

        assertThat(auditSink.events()).isEmpty();
    }

    @Test
    void consentMissingUserClaim_returns403_andDoesNotEmitAudit() {
        client.mutateWith(mockJwt().jwt(j -> j.subject("sub-x").claim("companyId", 1L)))
                .post().uri(CONSENTS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.message()).contains("userId"));

        assertThat(auditSink.events()).isEmpty();
    }

    @Test
    void consentInvalidHash_returns400_validationCode_andDoesNotEmitAudit() {
        asUser(1L, 990001L)
                .post().uri(CONSENTS_PATH)
                .bodyValue(new RecordingConsentRequest(
                        MEETING_ID,
                        CAPTURE_ID,
                        "recorder-consent-v1",
                        "not-a-real-hash",
                        "tr-TR"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_VALIDATION));

        assertThat(auditSink.events()).isEmpty();
    }

    @Test
    void consentMeetingAccessDenied_returns403_andDoesNotEmitAudit() {
        when(meetingAccessValidator.validate(any(), any(), any()))
                .thenReturn(Mono.just(Decision.forbidden("Meeting is not visible to caller")));

        asUser(1L, 990001L)
                .post().uri(CONSENTS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isForbidden()
                .expectBody(ErrorResponse.class)
                .value(err -> assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_FORBIDDEN));

        assertThat(auditSink.events()).isEmpty();
    }

    @Test
    void consentMeetingAccessUnavailable_returns503_retryable_andDoesNotEmitAudit() {
        when(meetingAccessValidator.validate(any(), any(), any()))
                .thenReturn(Mono.just(Decision.unavailable("Meeting access validation unavailable")));

        asUser(1L, 990001L)
                .post().uri(CONSENTS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_MEETING_VALIDATION_UNAVAILABLE);
                    assertThat(err.retryable()).isTrue();
                });

        assertThat(auditSink.events()).isEmpty();
    }

    @Test
    void consentAuditSinkFailure_returns503_retryable() {
        auditSink.throwOnEmit();

        asUser(1L, 990001L)
                .post().uri(CONSENTS_PATH)
                .bodyValue(validRequest())
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(ErrorResponse.class)
                .value(err -> {
                    assertThat(err.code()).isEqualTo(ErrorResponse.CODE_AUDIT_UNAVAILABLE);
                    assertThat(err.retryable()).isTrue();
                });
    }
}

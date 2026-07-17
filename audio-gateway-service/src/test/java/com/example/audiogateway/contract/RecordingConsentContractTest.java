package com.example.audiogateway.contract;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import com.example.audiogateway.dto.ErrorResponse;
import com.example.audiogateway.dto.RecordingConsentRequest;
import com.example.audiogateway.dto.RecordingConsentResponse;
import com.example.audiogateway.dto.RecordingConsentRevocationRequest;
import com.example.audiogateway.dto.RecordingConsentRevocationResponse;
import com.example.audiogateway.service.AudioGatewayAuditSink;
import com.example.audiogateway.service.AudioGatewayAuditSink.AuditEvent;
import com.example.audiogateway.service.MeetingAccessValidator;
import com.example.audiogateway.service.MeetingAccessValidator.Decision;
import java.util.List;
import java.util.UUID;
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
    private static final UUID TENANT_UUID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID ORG_UUID = UUID.fromString("55555555-5555-4555-8555-555555555555");
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
                .thenReturn(Mono.just(Decision.granted(TENANT_UUID, ORG_UUID)));
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
        verify(meetingAccessValidator).validate(eq(MEETING_ID), any(), any());
        assertThat(auditSink.events().get(0))
                .isInstanceOfSatisfying(AuditEvent.RecordingConsentGranted.class, event -> {
                    assertThat(event.meetingId()).isEqualTo(MEETING_ID);
                    assertThat(event.captureId()).isEqualTo(CAPTURE_ID);
                    assertThat(event.tenantId()).isEqualTo(1L);
                    assertThat(event.userId()).isEqualTo(990001L);
                    assertThat(event.canonicalTenantId()).isEqualTo(TENANT_UUID.toString());
                    assertThat(event.orgId()).isEqualTo(ORG_UUID.toString());
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

    @Test
    void consentRevoked_returns202_andEmitsCanonicalScopedAuditEvent() {
        final long before = System.currentTimeMillis();
        final RecordingConsentRevocationRequest request = new RecordingConsentRevocationRequest(
                MEETING_ID, CAPTURE_ID, "recorder-consent-v1", 2, "USER_WITHDREW");

        asUser(1L, 990001L)
                .post().uri(CONSENTS_PATH + "/revocations")
                .bodyValue(request)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(RecordingConsentRevocationResponse.class)
                .value(response -> {
                    assertThat(response.meetingId()).isEqualTo(MEETING_ID);
                    assertThat(response.captureId()).isEqualTo(CAPTURE_ID);
                    assertThat(response.consentRevision()).isEqualTo(2);
                    assertThat(response.reasonCode()).isEqualTo("USER_WITHDREW");
                    assertThat(response.revokedAtMs()).isBetween(before, System.currentTimeMillis());
                });

        assertThat(auditSink.events()).singleElement()
                .isInstanceOfSatisfying(AuditEvent.RecordingConsentRevoked.class, event -> {
                    assertThat(event.meetingId()).isEqualTo(MEETING_ID);
                    assertThat(event.captureId()).isEqualTo(CAPTURE_ID);
                    assertThat(event.tenantId()).isEqualTo(1L);
                    assertThat(event.userId()).isEqualTo(990001L);
                    assertThat(event.canonicalTenantId()).isEqualTo(TENANT_UUID.toString());
                    assertThat(event.orgId()).isEqualTo(ORG_UUID.toString());
                    assertThat(event.subjectId()).isEqualTo("sub-990001");
                    assertThat(event.consentVersion()).isEqualTo("recorder-consent-v1");
                    assertThat(event.consentRevision()).isEqualTo(2);
                    assertThat(event.reasonCode()).isEqualTo("USER_WITHDREW");
                });
    }

    @Test
    void consentRevoked_withoutCanonicalScope_failsClosedBeforeAudit() {
        when(meetingAccessValidator.validate(any(), any(), any()))
                .thenReturn(Mono.just(Decision.granted()));

        asUser(1L, 990001L)
                .post().uri(CONSENTS_PATH + "/revocations")
                .bodyValue(new RecordingConsentRevocationRequest(
                        MEETING_ID, CAPTURE_ID, "recorder-consent-v1", 2, "USER_WITHDREW"))
                .exchange()
                .expectStatus().isEqualTo(503)
                .expectBody(ErrorResponse.class)
                .value(error -> {
                    assertThat(error.code()).isEqualTo(ErrorResponse.CODE_MEETING_VALIDATION_UNAVAILABLE);
                    assertThat(error.retryable()).isTrue();
                });

        assertThat(auditSink.events()).isEmpty();
    }

    @Test
    void consentRevoked_revisionOutsideCurrentOccurrence_isRejectedBeforeAudit() {
        for (long invalidRevision : java.util.List.of(1L, 3L)) {
            asUser(1L, 990001L)
                    .post().uri(CONSENTS_PATH + "/revocations")
                    .bodyValue(new RecordingConsentRevocationRequest(
                            MEETING_ID, CAPTURE_ID, "recorder-consent-v1",
                            invalidRevision, "USER_WITHDREW"))
                    .exchange()
                    .expectStatus().isBadRequest();
        }

        assertThat(auditSink.events()).isEmpty();
    }
}

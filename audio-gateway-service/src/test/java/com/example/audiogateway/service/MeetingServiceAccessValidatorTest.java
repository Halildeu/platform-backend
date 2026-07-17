package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.MeetingAccessValidator.Decision;

import java.util.concurrent.atomic.AtomicReference;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

class MeetingServiceAccessValidatorTest {

    private static final String MEETING_ID = "22222222-2222-4222-8222-222222222222";
    private static final UUID TENANT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID ORG_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");

    @Test
    void enabledValidation_parsesCanonicalTenantScopeFromAuthorizedResponse() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        WebClient webClient = WebClient.builder()
                .baseUrl("http://meeting-service:8097")
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.OK)
                            .header(HttpHeaders.CONTENT_TYPE, "application/json")
                            .body("{\"meetingId\":\"" + MEETING_ID + "\","
                                    + "\"tenantId\":\"" + TENANT_ID + "\","
                                    + "\"orgId\":\"" + ORG_ID + "\"}")
                            .build());
                })
                .build();

        Decision decision = new MeetingServiceAccessValidator(props(true), webClient)
                .validate(MEETING_ID, jwt(), "corr-scope")
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tenantId()).isEqualTo(TENANT_ID);
        assertThat(decision.orgId()).isEqualTo(ORG_ID);
    }

    @Test
    void enabledValidation_forwardsBearerAndCorrelation_allows2xx() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        String body = "{\"meetingId\":\"" + MEETING_ID + "\","
                + "\"tenantId\":\"" + TENANT_ID + "\","
                + "\"orgId\":\"" + ORG_ID + "\"}";
        MeetingServiceAccessValidator validator = validator(HttpStatus.OK, captured, body);

        Decision decision = validator.validate(MEETING_ID, jwt(), "corr-123").block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(captured.get().url().getPath())
                .isEqualTo("/api/v1/meetings/" + MEETING_ID + "/recording-access");
        assertThat(captured.get().headers().getFirst(HttpHeaders.AUTHORIZATION))
                .isEqualTo("Bearer jwt-fixture");
        assertThat(captured.get().headers().getFirst("X-Correlation-Id"))
                .isEqualTo("corr-123");
    }

    @Test
    void meetingNotVisible_deniesWithoutExistenceLeak() {
        Decision decision = validator(HttpStatus.NOT_FOUND, new AtomicReference<>())
                .validate(MEETING_ID, jwt(), "corr-404")
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(decision.retryable()).isFalse();
        assertThat(decision.message()).isEqualTo("Meeting is not visible to caller");
    }

    @Test
    void meetingServiceServerError_returnsRetryableUnavailable() {
        Decision decision = validator(HttpStatus.INTERNAL_SERVER_ERROR, new AtomicReference<>())
                .validate(MEETING_ID, jwt(), "corr-500")
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(decision.retryable()).isTrue();
    }

    @Test
    void canonicalScopeForDifferentMeeting_failsClosed() {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://meeting-service:8097")
                .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header(HttpHeaders.CONTENT_TYPE, "application/json")
                        .body("{\"meetingId\":\"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa\","
                                + "\"tenantId\":\"" + TENANT_ID + "\","
                                + "\"orgId\":\"" + ORG_ID + "\"}")
                        .build()))
                .build();

        Decision decision = new MeetingServiceAccessValidator(props(true), webClient)
                .validate(MEETING_ID, jwt(), "corr-mismatch")
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(decision.retryable()).isTrue();
    }

    @Test
    void authorizedEmptyBody_allowsLegacySessionWithoutCanonicalScope() {
        Decision decision = validator(HttpStatus.OK, new AtomicReference<>())
                .validate(MEETING_ID, jwt(), "corr-empty")
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(decision.tenantId()).isNull();
        assertThat(decision.orgId()).isNull();
    }

    @Test
    void transportFailure_returnsRetryableUnavailable() {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://meeting-service:8097")
                .exchangeFunction(request -> Mono.error(new IllegalStateException("connection refused")))
                .build();

        Decision decision = new MeetingServiceAccessValidator(props(true), webClient)
                .validate(MEETING_ID, jwt(), "corr-io")
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(decision.retryable()).isTrue();
        assertThat(decision.message()).isEqualTo("Meeting access validation unavailable");
    }

    @Test
    void disabledValidation_allowsWithoutCallingMeetingService() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        AudioGatewayProperties props = props(false);
        WebClient webClient = WebClient.builder()
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR).build());
                })
                .build();

        Decision decision = new MeetingServiceAccessValidator(props, webClient)
                .validate(MEETING_ID, jwt(), "corr-disabled")
                .block();

        assertThat(decision).isNotNull();
        assertThat(decision.allowed()).isTrue();
        assertThat(captured).hasValue(null);
    }

    private static MeetingServiceAccessValidator validator(
            final HttpStatus status,
            final AtomicReference<ClientRequest> captured) {
        return validator(status, captured, null);
    }

    private static MeetingServiceAccessValidator validator(
            final HttpStatus status,
            final AtomicReference<ClientRequest> captured,
            final String body) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://meeting-service:8097")
                .exchangeFunction(request -> {
                    captured.set(request);
                    ClientResponse.Builder response = ClientResponse.create(status);
                    if (body != null) {
                        response.header(HttpHeaders.CONTENT_TYPE, "application/json").body(body);
                    }
                    return Mono.just(response.build());
                })
                .build();
        return new MeetingServiceAccessValidator(props(true), webClient);
    }

    private static AudioGatewayProperties props(final boolean enabled) {
        AudioGatewayProperties props = new AudioGatewayProperties();
        props.getMeetingAccess().setValidationEnabled(enabled);
        return props;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("jwt-fixture")
                .headers(headers -> headers.put("alg", "none"))
                .claim("sub", "user-1")
                .build();
    }
}

package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;
import com.example.audiogateway.service.MeetingAccessValidator.Decision;

import java.util.concurrent.atomic.AtomicReference;
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

    @Test
    void enabledValidation_forwardsBearerAndCorrelation_allows2xx() {
        AtomicReference<ClientRequest> captured = new AtomicReference<>();
        MeetingServiceAccessValidator validator = validator(HttpStatus.OK, captured);

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
        WebClient webClient = WebClient.builder()
                .baseUrl("http://meeting-service:8097")
                .exchangeFunction(request -> {
                    captured.set(request);
                    return Mono.just(ClientResponse.create(status).build());
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

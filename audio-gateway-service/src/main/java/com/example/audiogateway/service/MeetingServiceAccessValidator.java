package com.example.audiogateway.service;

import com.example.audiogateway.config.AudioGatewayProperties;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * meeting-service backed access check for canonical meeting UUIDs.
 *
 * <p>The gateway forwards the caller's bearer token to the bodyless
 * {@code GET /api/v1/meetings/{id}/recording-access} preflight endpoint.
 * A 2xx response means object-level {@code meeting:{id}#can_record} passed
 * and capture may start. 401/403/404 deny without an existence leak;
 * transport/5xx failures fail closed as retryable 503. The response body is
 * intentionally ignored so transcript/audio/meeting details never enter
 * gateway logs or metrics.
 */
public class MeetingServiceAccessValidator implements MeetingAccessValidator {

    private static final String MEETING_PATH = "/api/v1/meetings/{meetingId}/recording-access";

    private final AudioGatewayProperties props;
    private final WebClient webClient;

    public MeetingServiceAccessValidator(
            final AudioGatewayProperties props,
            final WebClient webClient) {
        this.props = props;
        this.webClient = webClient;
    }

    @Override
    public Mono<Decision> validate(final String meetingId, final Jwt jwt, final String correlationId) {
        final AudioGatewayProperties.MeetingAccess cfg = props.getMeetingAccess();
        if (!cfg.isValidationEnabled()) {
            return Mono.just(Decision.granted());
        }
        if (jwt == null || jwt.getTokenValue() == null || jwt.getTokenValue().isBlank()) {
            return Mono.just(Decision.forbidden("JWT missing for meeting access validation"));
        }

        return webClient.get()
                .uri(MEETING_PATH, meetingId)
                .headers(headers -> {
                    headers.setBearerAuth(jwt.getTokenValue());
                    if (correlationId != null && !correlationId.isBlank()) {
                        headers.set(HttpHeaders.CACHE_CONTROL, "no-store");
                        headers.set("X-Correlation-Id", correlationId);
                    }
                })
                .exchangeToMono(response -> response.releaseBody().thenReturn(decisionFor(response.statusCode())))
                .timeout(Duration.ofMillis(cfg.getResponseTimeoutMs()))
                .onErrorResume(ex -> Mono.just(Decision.unavailable("Meeting access validation unavailable")));
    }

    private static Decision decisionFor(final HttpStatusCode status) {
        if (status.is2xxSuccessful()) {
            return Decision.granted();
        }
        if (status.value() == 401 || status.value() == 403 || status.value() == 404
                || status.is4xxClientError()) {
            return Decision.forbidden("Meeting is not visible to caller");
        }
        return Decision.unavailable("Meeting access validation unavailable");
    }
}

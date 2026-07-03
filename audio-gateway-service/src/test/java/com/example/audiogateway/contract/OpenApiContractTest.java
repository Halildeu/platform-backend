package com.example.audiogateway.contract;

import com.example.audiogateway.service.MeetingAccessValidator;
import com.example.audiogateway.service.MeetingAccessValidator.Decision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

/**
 * OpenAPI contract surface test (#424) — springdoc auto-gen {@code /v3/api-docs}.
 *
 * <p>Narrow JSONPath assertion (notification-orchestrator {@code OpenApiContractTest}
 * pattern) — not a golden-file snapshot, so it stays resilient to unrelated spec
 * churn while still proving the deliverable (a machine-readable OpenAPI document
 * exists and covers the real contract paths).
 *
 * <p>{@code /v3/api-docs} is NOT added to the {@code SecurityConfig} permitAll list:
 * this service is fail-closed by default, so the endpoint is reachable only with a
 * valid JWT like every other non-actuator route. Swagger UI is intentionally not
 * bundled; #424 requires a machine-readable spec, not a new browser UI surface.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test"
})
class OpenApiContractTest {

    @Autowired
    private WebTestClient client;

    @MockitoBean
    private MeetingAccessValidator meetingAccessValidator;

    @BeforeEach
    void allowMeetingAccess() {
        when(meetingAccessValidator.validate(any(), any(), any()))
                .thenReturn(Mono.just(Decision.granted()));
    }

    private WebTestClient withClaims() {
        return client.mutateWith(mockJwt().jwt(j -> j.claim("companyId", 1L).claim("userId", 42L)));
    }

    @Test
    void noAuth_returns401() {
        client.get().uri("/v3/api-docs").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void swaggerUiIsNotBundled() {
        withClaims().get().uri("/swagger-ui.html").exchange().expectStatus().isNotFound();
    }

    @Test
    void openApiContainsSessionEndpoints() {
        withClaims().get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths./api/v1/audio-gateway/sessions").exists()
                .jsonPath("$.paths./api/v1/audio-gateway/sessions.post").exists()
                .jsonPath("$.paths./api/v1/audio-gateway/sessions/{sessionId}/finish").exists()
                .jsonPath("$.paths./api/v1/audio-gateway/sessions/{sessionId}/finish.post").exists();
    }

    @Test
    void openApiContainsChunkAndConsentEndpoints() {
        withClaims().get().uri("/v3/api-docs").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.paths./api/v1/audio-gateway/consents.post").exists()
                .jsonPath("$.paths./api/v1/audio-gateway/sessions/{sessionId}/chunks.post").exists()
                .jsonPath("$.paths./api/v1/audio-gateway/sessions/{sessionId}/status.get").exists();
    }
}

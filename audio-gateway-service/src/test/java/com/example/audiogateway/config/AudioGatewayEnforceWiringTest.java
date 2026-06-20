package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

/**
 * #716 Step-7 WIRING smoke (Codex 019ee73d) — proves the REAL resource-server chain
 * {@code Bearer -> ReactiveJwtDecoder -> jwtAuthenticationConverter -> hasAuthority} is
 * actually wired, not just the pieces in isolation.
 *
 * <p>Only the decoder is stubbed (signature/JWKS is Nimbus's concern + the audience leg
 * is covered by {@link AudienceValidatorTest}); the converter + filter chain are the REAL
 * production beans. A wiring regression — converter not registered in
 * {@code oauth2ResourceServer().jwt(...)}, wrong bean, or a mis-bound {@code resourceClientId}
 * property — would make the with-role bearer 403 instead of 404, failing this test.
 * mockJwt-based tests cannot catch that class of bug because they bypass decoder+converter.
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test",
        "audio-gateway.security.require-audio-record-role=true"
})
@Import(AudioGatewayEnforceWiringTest.StubDecoderConfig.class)
class AudioGatewayEnforceWiringTest {

    private static final String SECURED =
            "/api/v1/audio-gateway/sessions/00000000-0000-0000-0000-000000000000/status";

    @Autowired
    private WebTestClient webTestClient;

    @TestConfiguration
    static class StubDecoderConfig {
        /**
         * Maps a bearer token value to a canned Jwt so the request flows through the REAL
         * converter + filter. "with-role" carries {@code resource_access.audio-gateway-service.roles};
         * "no-role" omits it. Both carry tenant claims so the role gate is isolated from the
         * controller's companyId/userId gate.
         */
        @Bean
        @Primary
        ReactiveJwtDecoder stubJwtDecoder() {
            return token -> {
                final Jwt.Builder b = Jwt.withTokenValue(token).header("alg", "none")
                        .subject("user-1").claim("companyId", 1).claim("userId", 1);
                if ("with-role".equals(token)) {
                    b.claim("resource_access",
                            Map.of("audio-gateway-service", Map.of("roles", List.of("audio_record"))));
                }
                return Mono.just(b.build());
            };
        }
    }

    @Test
    void bearerWithRoleClaimIsWiredThroughConverterAndPasses() {
        // resource_access role -> real converter -> audio_record authority -> hasAuthority
        // passes -> controller -> 404 (session not found). Proves the chain is connected.
        webTestClient.get().uri(SECURED).headers(h -> h.setBearerAuth("with-role"))
                .exchange().expectStatus().isNotFound();
    }

    @Test
    void bearerWithoutRoleClaimIsForbidden() {
        // No resource_access role -> converter yields no audio_record -> filter denies 403
        // (filter-chain, not the controller tenant gate — body has no MEETING_FORBIDDEN).
        webTestClient.get().uri(SECURED).headers(h -> h.setBearerAuth("no-role"))
                .exchange().expectStatus().isForbidden()
                // Filter-chain 403 has no body; controller tenant 403 carries the JSON code.
                .expectBody(String.class)
                .value(body -> assertThat(body == null ? "" : body)
                        .doesNotContain("AUDIO_GATEWAY_MEETING_FORBIDDEN"));
    }
}

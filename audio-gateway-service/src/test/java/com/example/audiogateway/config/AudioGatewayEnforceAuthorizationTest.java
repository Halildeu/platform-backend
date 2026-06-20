package com.example.audiogateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * #716 Step-7 enforce-flip readiness — fail-closed authorization proof.
 *
 * <p>With {@code audio-gateway.security.require-audio-record-role=true} the filter chain
 * demands {@code hasAuthority("audio_record")} on every non-public endpoint
 * ({@link SecurityConfig#securityFilterChain}). This test pins the gate the operator
 * relies on at the enforce-flip:
 * <ul>
 *   <li>no token            &rarr; 401 (unauthenticated)</li>
 *   <li>token without role  &rarr; 403 (authenticated, capability missing)</li>
 *   <li>token with role      &rarr; security passes (not 401/403)</li>
 * </ul>
 * Composed with {@code AudioGatewaySecurityConfigConverterTest} (extraction) +
 * {@link AudienceValidatorTest} (audience), the full enforce path is machine-enforced
 * BEFORE the flip — no "flip and pray".
 */
@SpringBootTest
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
        "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9999/realms/test",
        "audio-gateway.security.require-audio-record-role=true"
})
class AudioGatewayEnforceAuthorizationTest {

    /** Any secured endpoint; a random (non-existent) session id is fine — we assert on
     *  the security verdict (401/403), not on business behaviour behind the gate. */
    private static final String SECURED =
            "/api/v1/audio-gateway/sessions/00000000-0000-0000-0000-000000000000/status";

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void noTokenIsUnauthorized() {
        webTestClient.get().uri(SECURED).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void tokenWithoutAudioRecordRoleIsForbidden() {
        // Codex 019ee73d: supply companyId/userId so the controller tenant gate is NOT
        // the cause of 403 — if the role gate were off/broken, this request would reach
        // the controller and 404 (not 403). The body-not-MEETING_FORBIDDEN assertion pins
        // the 403 to the filter-chain role denial, never the controller's tenant 403.
        webTestClient
                .mutateWith(mockJwt()
                        .jwt(jwt -> jwt.claim("companyId", 1).claim("userId", 1))
                        .authorities(new SimpleGrantedAuthority("SCOPE_profile")))
                .get().uri(SECURED).exchange()
                .expectStatus().isForbidden()
                // Filter-chain 403 has no body; controller tenant 403 carries the JSON
                // code. null/empty body therefore confirms the filter-chain (role) denial.
                .expectBody(String.class)
                .value(body -> assertThat(body == null ? "" : body)
                        .doesNotContain("AUDIO_GATEWAY_MEETING_FORBIDDEN"));
    }

    @Test
    void tokenWithAudioRecordRolePassesSecurity() {
        // Role gate + tenant/user claims satisfied → request clears ALL security and
        // reaches business logic → 404 for a non-existent session (NOT 401/403). This is
        // the positive proof that audio_record admits; companyId/userId are the
        // controller's pre-existing tenant isolation, supplied so the role gate is isolated.
        webTestClient
                .mutateWith(mockJwt()
                        .jwt(jwt -> jwt.claim("companyId", 1).claim("userId", 1))
                        .authorities(new SimpleGrantedAuthority("audio_record")))
                .get().uri(SECURED).exchange()
                .expectStatus().isNotFound();
    }
}

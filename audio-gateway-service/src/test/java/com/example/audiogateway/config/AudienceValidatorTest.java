package com.example.audiogateway.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * AudienceValidator unit tests (#716, cross-AI 019ee16b).
 * Default-off no-op + enforced accept/reject behaviour.
 */
class AudienceValidatorTest {

    private static final String AUD = "audio-gateway-service";

    private static Jwt jwt(final List<String> audience) {
        final Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "RS256").claim("sub", "u");
        if (audience != null) {
            b.claim("aud", audience);
        }
        return b.build();
    }

    @Test
    void notEnforced_isNoop_evenWithWrongAudience() {
        assertFalse(new AudienceValidator(false, AUD).validate(jwt(List.of("frontend"))).hasErrors());
    }

    @Test
    void enforced_withMatchingAudience_succeeds() {
        assertFalse(new AudienceValidator(true, AUD).validate(jwt(List.of("frontend", AUD))).hasErrors());
    }

    @Test
    void enforced_withoutMatchingAudience_fails() {
        assertTrue(new AudienceValidator(true, AUD).validate(jwt(List.of("frontend"))).hasErrors());
    }

    @Test
    void enforced_withNullAudience_fails() {
        assertTrue(new AudienceValidator(true, AUD).validate(jwt(null)).hasErrors());
    }

    @Test
    void enforced_withBlankRequiredAudience_isNoop() {
        assertFalse(new AudienceValidator(true, "  ").validate(jwt(List.of("x"))).hasErrors());
    }
}

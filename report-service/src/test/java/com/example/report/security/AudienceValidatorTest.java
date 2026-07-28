package com.example.report.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {
    private final AudienceValidator validator =
            new AudienceValidator(List.of("report-service"), List.of("frontend"));

    @Test
    void acceptsFrontendTokenRelayedByBudgetService() {
        Jwt token = jwt(List.of("account"), Map.of("azp", "frontend"));
        assertThat(validator.validate(token).getErrors()).isEmpty();
    }

    @Test
    void rejectsUnknownRelayedClient() {
        Jwt token = jwt(List.of("account"), Map.of("azp", "unknown-client"));
        assertThat(validator.validate(token).getErrors()).isNotEmpty();
    }

    private Jwt jwt(List<String> audience, Map<String, Object> extraClaims) {
        Jwt.Builder builder = Jwt.withTokenValue("synthetic")
                .header("alg", "none")
                .subject("subject-1")
                .audience(audience)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60));
        extraClaims.forEach(builder::claim);
        return builder.build();
    }
}

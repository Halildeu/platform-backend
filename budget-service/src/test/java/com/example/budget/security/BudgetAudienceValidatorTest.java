package com.example.budget.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class BudgetAudienceValidatorTest {
    private final BudgetAudienceValidator validator =
            new BudgetAudienceValidator(List.of("budget-service"), List.of("frontend"));

    @Test
    void acceptsDirectBudgetAudience() {
        assertThat(validator.validate(jwt(List.of("budget-service"), Map.of())).getErrors())
                .isEmpty();
    }

    @Test
    void acceptsRelayedFrontendTokenByAuthorizedParty() {
        assertThat(validator.validate(jwt(List.of("account"), Map.of("azp", "frontend")))
                .getErrors()).isEmpty();
    }

    @Test
    void rejectsUnrecognizedAudienceAndClient() {
        assertThat(validator.validate(jwt(List.of("account"), Map.of("azp", "unknown-client")))
                .getErrors()).isNotEmpty();
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

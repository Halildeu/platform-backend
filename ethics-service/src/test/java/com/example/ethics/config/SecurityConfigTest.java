package com.example.ethics.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class SecurityConfigTest {
    private static final String ISSUER = "https://testai.acik.com/realms/platform-test";
    private static final EthicsProperties PROPERTIES = new EthicsProperties(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            Duration.ofMinutes(15),
            210_000,
            "ethics-manager",
            "ethics-manager",
            true);

    @Test
    void acceptsOnlyTokenWithIssuerAudienceAndPersonaRole() {
        var result = SecurityConfig.staffJwtValidator(ISSUER, PROPERTIES)
                .validate(token(List.of("ethics-manager"), List.of("ethics-manager")));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsAudienceWithoutPersonaRole() {
        var result = SecurityConfig.staffJwtValidator(ISSUER, PROPERTIES)
                .validate(token(List.of("ethics-manager"), List.of("default-roles-platform-test")));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsPersonaRoleWithoutDedicatedAudience() {
        var result = SecurityConfig.staffJwtValidator(ISSUER, PROPERTIES)
                .validate(token(List.of("frontend"), List.of("ethics-manager")));

        assertThat(result.hasErrors()).isTrue();
    }

    private Jwt token(List<String> audience, List<String> roles) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .issuer(ISSUER)
                .issuedAt(now.minusSeconds(5))
                .expiresAt(now.plusSeconds(300))
                .audience(audience)
                .claim("realm_access", Map.of("roles", roles))
                .build();
    }
}

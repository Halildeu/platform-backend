package com.example.user.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class AudienceOrAuthorizedPartyValidatorTest {

    @Test
    void validate_should_accept_matching_audience() {
        AudienceOrAuthorizedPartyValidator validator =
                new AudienceOrAuthorizedPartyValidator(
                        java.util.List.of("user-service", "frontend"),
                        java.util.List.of("frontend", "admin-cli")
                );

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .audience(java.util.List.of("frontend"))
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void validate_should_accept_matching_authorized_party_when_audience_missing() {
        AudienceOrAuthorizedPartyValidator validator =
                new AudienceOrAuthorizedPartyValidator(
                        java.util.List.of("user-service", "frontend"),
                        java.util.List.of("frontend", "admin-cli")
                );

        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("user")
                .claim("azp", "admin-cli")
                .build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }
}

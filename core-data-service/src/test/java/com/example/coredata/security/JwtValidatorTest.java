package com.example.coredata.security;

import java.util.List;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

class JwtValidatorTest {

    private static final String ISSUER = "http://issuer.example.com/realms/serban";
    private static final String AUDIENCE = "core-data-service";

    private final OAuth2TokenValidator<Jwt> validator = new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(ISSUER),
            new AudienceValidator(List.of(AUDIENCE))
    );

    @Test
    void acceptsValidIssuerAndAudience() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuer(ISSUER)
                .audience(List.of(AUDIENCE))
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);
        Assertions.assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsMissingAudience() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuer(ISSUER)
                .audience(List.of("other-service"))
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);
        Assertions.assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void rejectsWrongIssuer() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .issuer("http://attacker.example.com")
                .audience(List.of(AUDIENCE))
                .build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);
        Assertions.assertThat(result.hasErrors()).isTrue();
    }
}

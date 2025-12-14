package com.example.variant.security;

import java.util.Collection;
import java.util.Objects;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class AudienceValidator implements OAuth2TokenValidator<Jwt> {
    private final String expectedAudience;

    public AudienceValidator(String expectedAudience) {
        this.expectedAudience = expectedAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (expectedAudience == null || expectedAudience.isBlank()) {
            return OAuth2TokenValidatorResult.success();
        }
        Collection<String> audiences = token.getAudience();
        if (audiences != null && audiences.stream().filter(Objects::nonNull).anyMatch(expectedAudience::equals)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The required audience is missing", null));
    }
}


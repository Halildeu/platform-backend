package com.example.report.security;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> expectedAudiences;

    public AudienceValidator(Collection<String> expectedAudiences) {
        if (expectedAudiences == null) {
            this.expectedAudiences = Set.of();
        } else {
            this.expectedAudiences = expectedAudiences.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (expectedAudiences.isEmpty()) {
            return OAuth2TokenValidatorResult.success();
        }

        Collection<String> tokenAudiences = token.getAudience();
        boolean match = tokenAudiences != null && tokenAudiences.stream()
                .filter(Objects::nonNull)
                .anyMatch(expectedAudiences::contains);

        if (match) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The required audience is missing", null)
        );
    }
}

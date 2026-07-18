package com.example.transcript.security;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/** Requires an auth-service token to identify one explicitly trusted service client. */
public final class ExpectedServiceClientValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error ERROR = new OAuth2Error(
            "invalid_token", "Unexpected service client identity", null);

    private final Set<String> expectedClientIds;

    public ExpectedServiceClientValidator(Collection<String> expectedClientIds) {
        this.expectedClientIds = expectedClientIds == null ? Set.of() : expectedClientIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
        if (this.expectedClientIds.isEmpty()) {
            throw new IllegalArgumentException("Expected service client allowlist must not be empty");
        }
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String clientId = token.getClaimAsString("client_id");
        String subject = token.getSubject();
        if (clientId == null || subject == null || !clientId.equals(subject)
                || !expectedClientIds.contains(clientId)) {
            return OAuth2TokenValidatorResult.failure(ERROR);
        }
        return OAuth2TokenValidatorResult.success();
    }
}

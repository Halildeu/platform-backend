package com.example.coredata.security;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates that at least one of the expected audiences is present on the token,
 * or that the token's authorized party (azp/client_id) is in the allowed list.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> expectedAudiences;
    private final Set<String> allowedClientIds;

    public AudienceValidator(Collection<String> expectedAudiences) {
        this(expectedAudiences, Set.of());
    }

    public AudienceValidator(Collection<String> expectedAudiences,
                             Collection<String> allowedClientIds) {
        this.expectedAudiences = normalize(expectedAudiences);
        this.allowedClientIds = normalize(allowedClientIds);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (expectedAudiences.isEmpty() && allowedClientIds.isEmpty()) {
            return OAuth2TokenValidatorResult.success();
        }

        // 1. Check aud claim
        Collection<String> tokenAudiences = token.getAudience();
        if (tokenAudiences != null && tokenAudiences.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(expectedAudiences::contains)) {
            return OAuth2TokenValidatorResult.success();
        }

        // 2. Check azp (authorized party) claim
        String azp = stringClaim(token, "azp");
        if (azp != null && allowedClientIds.contains(azp)) {
            return OAuth2TokenValidatorResult.success();
        }

        // 3. Check client_id claim
        String clientId = stringClaim(token, "client_id");
        if (clientId != null && allowedClientIds.contains(clientId)) {
            return OAuth2TokenValidatorResult.success();
        }

        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The required audience is missing", null)
        );
    }

    private static String stringClaim(Jwt token, String claimName) {
        Object claim = token.getClaims().get(claimName);
        if (claim instanceof String value) {
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }

    private static Set<String> normalize(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        return values.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }
}

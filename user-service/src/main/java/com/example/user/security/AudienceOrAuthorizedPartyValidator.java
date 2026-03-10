package com.example.user.security;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Local/dev ortamında Keycloak browser tokenlari bazen aud claim'i tasimadan
 * azp/client_id uzerinden istemciyi bildirir. Bu validator canonical audience
 * kontrolunu korur, ama ek olarak izinli authorized party/client id listesiyle
 * local tarayici tokenlarinin da kabul edilmesini saglar.
 */
public class AudienceOrAuthorizedPartyValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> expectedAudiences;
    private final Set<String> allowedClientIds;

    public AudienceOrAuthorizedPartyValidator(Collection<String> expectedAudiences,
                                              Collection<String> allowedClientIds) {
        this.expectedAudiences = normalize(expectedAudiences);
        this.allowedClientIds = normalize(allowedClientIds);
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        if (matchesAudience(token) || matchesAuthorizedParty(token)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The required audience or authorized party is missing", null));
    }

    private boolean matchesAudience(Jwt token) {
        if (expectedAudiences.isEmpty()) {
            return false;
        }
        Collection<String> tokenAudiences = token.getAudience();
        return tokenAudiences != null && tokenAudiences.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .anyMatch(expectedAudiences::contains);
    }

    private boolean matchesAuthorizedParty(Jwt token) {
        if (allowedClientIds.isEmpty()) {
            return false;
        }
        String azp = stringClaim(token, "azp");
        if (azp != null && allowedClientIds.contains(azp)) {
            return true;
        }
        String clientId = stringClaim(token, "client_id");
        return clientId != null && allowedClientIds.contains(clientId);
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

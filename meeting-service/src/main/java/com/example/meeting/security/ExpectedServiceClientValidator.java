package com.example.meeting.security;

import java.util.Collection;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Requires an auth-service access token to identify one explicitly trusted OAuth client.
 *
 * <p>Audience validation and client validation are deliberately separate and combined with
 * AND semantics by the internal decoder. {@code client_id} is the RFC 9068 client claim;
 * {@code sub} must identify the same client for a client-credentials grant with no resource
 * owner. OIDC-specific {@code azp} and the legacy {@code svc} claim are not authorization
 * inputs, avoiding four-way claim drift while {@code svc} remains available to older
 * downstream service-token consumers.
 */
public final class ExpectedServiceClientValidator implements OAuth2TokenValidator<Jwt> {

    private final Set<String> expectedClientIds;

    public ExpectedServiceClientValidator(Collection<String> expectedClientIds) {
        this.expectedClientIds = expectedClientIds == null
                ? Set.of()
                : expectedClientIds.stream()
                        .filter(Objects::nonNull)
                        .map(String::trim)
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public OAuth2TokenValidatorResult validate(Jwt token) {
        String clientId = stringClaim(token, "client_id");
        String subject = stringClaim(token, "sub");
        if (!expectedClientIds.isEmpty()
                && clientId != null
                && clientId.equals(subject)
                && expectedClientIds.contains(clientId)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                "invalid_token",
                "The required service client identity is missing",
                null));
    }

    private static String stringClaim(Jwt token, String claimName) {
        Object claim = token.getClaims().get(claimName);
        if (claim instanceof String value) {
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
        return null;
    }
}

package com.example.audiogateway.config;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Validates that the JWT {@code aud} claim targets this resource server
 * (Faz 24, platform-backend#716; cross-AI consensus 019ee16b).
 *
 * <p>DEFAULT-OFF: when {@code enforceAudience=false} this is a no-op
 * ({@link OAuth2TokenValidatorResult#success()}) so behaviour is unchanged until
 * the migration enforce-flip. When enabled, a token whose {@code aud} does not
 * contain {@code resourceClientId} is rejected ({@code invalid_token}).
 *
 * <p>Layered with the default issuer + timestamp validators via
 * {@code DelegatingOAuth2TokenValidator} — does NOT replace them.
 */
public final class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private static final OAuth2Error MISSING = new OAuth2Error(
            "invalid_token",
            "The required audience is missing from the token",
            null);

    private final boolean enforce;
    private final String requiredAudience;

    public AudienceValidator(final boolean enforce, final String requiredAudience) {
        this.enforce = enforce;
        this.requiredAudience = requiredAudience;
    }

    @Override
    public OAuth2TokenValidatorResult validate(final Jwt jwt) {
        if (!enforce || requiredAudience == null || requiredAudience.isBlank()) {
            return OAuth2TokenValidatorResult.success();
        }
        final List<String> aud = jwt.getAudience();
        if (aud != null && aud.contains(requiredAudience)) {
            return OAuth2TokenValidatorResult.success();
        }
        return OAuth2TokenValidatorResult.failure(MISSING);
    }
}

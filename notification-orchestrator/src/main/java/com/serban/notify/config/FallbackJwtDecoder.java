package com.serban.notify.config;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Tries several trust sources in order (#734, ported from permission-service).
 *
 * <p>notification-orchestrator accepts user-facing Keycloak tokens (primary)
 * AND, when configured, short-lived auth-service service tokens (secondary)
 * used by the internal system-submit path ({@code /api/v1/internal/notify/**}).
 * Each delegate is attempted in turn; the first that decodes wins. If all fail,
 * the first failure is rethrown (with the others suppressed) so the original
 * (user-token) error remains the primary diagnostic.
 */
public final class FallbackJwtDecoder implements JwtDecoder {

    private final List<JwtDecoder> delegates;

    public FallbackJwtDecoder(List<JwtDecoder> delegates) {
        if (delegates == null || delegates.isEmpty()) {
            throw new IllegalArgumentException("At least one JWT decoder delegate is required");
        }
        this.delegates = List.copyOf(delegates);
    }

    @Override
    public Jwt decode(String token) throws JwtException {
        JwtException lastFailure = null;
        for (JwtDecoder delegate : delegates) {
            try {
                return delegate.decode(token);
            } catch (JwtException ex) {
                if (lastFailure != null) {
                    lastFailure.addSuppressed(ex);
                } else {
                    lastFailure = ex;
                }
            }
        }
        if (lastFailure != null) {
            throw lastFailure;
        }
        throw new JwtException("No JWT decoder was able to decode the token");
    }
}

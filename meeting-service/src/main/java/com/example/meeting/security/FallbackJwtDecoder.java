package com.example.meeting.security;

import java.util.List;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Tries several trust sources in order (ai#244 BE-1b, ported from
 * notification-orchestrator {@code #734} / permission-service).
 *
 * <p>meeting-service accepts user-facing Keycloak tokens (primary) AND, when
 * configured, short-lived auth-service SERVICE tokens (secondary) used by the
 * internal analysis-result ingestion path ({@code /api/v1/internal/meetings/**}).
 * Each delegate is attempted in turn; the first that decodes wins.
 *
 * <h3>Config/infra errors are NOT masked (Codex acceptance condition)</h3>
 *
 * <p>Only a {@link BadJwtException} — the per-token validation-failure family —
 * triggers fallback to the next delegate. This deliberately includes
 * {@link org.springframework.security.oauth2.jwt.JwtValidationException} (a failed
 * issuer/audience/expiry validator), which <em>extends</em> {@code BadJwtException}
 * in Spring Security, so a service token rejected by the Keycloak decoder's issuer
 * validator falls through to the service decoder — the very reason this class exists.
 * It also covers a bad signature and a malformed token. A
 * {@link org.springframework.security.oauth2.jwt.JwtDecoderInitializationException}
 * (e.g. the JWK-set endpoint is unreachable) is a {@code RuntimeException}, NOT a
 * {@code BadJwtException}, so it is never caught here: it propagates immediately
 * and is surfaced as a genuine infrastructure fault rather than being silently
 * swallowed and re-reported as a generic "invalid token" 401. If every delegate
 * reports a {@code BadJwtException}, the first failure is rethrown (with the
 * others attached as suppressed) so the original (user-token) diagnostic stays
 * primary.
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
        BadJwtException lastFailure = null;
        for (JwtDecoder delegate : delegates) {
            try {
                return delegate.decode(token);
            } catch (BadJwtException ex) {
                // Expected per-token validation failure — try the next trust
                // source. Any other exception (notably
                // JwtDecoderInitializationException) is deliberately NOT caught
                // and propagates so a config/infra fault is never masked.
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
        throw new BadJwtException("No JWT decoder was able to decode the token");
    }
}

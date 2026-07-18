package com.example.transcript.security;

import java.util.List;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/** Tries user and service-token trust sources without hiding infrastructure failures. */
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
        BadJwtException firstFailure = null;
        for (JwtDecoder delegate : delegates) {
            try {
                return delegate.decode(token);
            } catch (BadJwtException ex) {
                if (firstFailure == null) {
                    firstFailure = ex;
                } else {
                    firstFailure.addSuppressed(ex);
                }
            }
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
        throw new BadJwtException("No JWT decoder accepted the token");
    }
}

package com.example.permission.security;

import java.util.List;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

/**
 * Birden fazla güven kaynağını sırayla dener. Bu sayede local/demo ortamında
 * hem Keycloak hem auth-service imzalı kullanıcı token'ları kabul edilebilir.
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

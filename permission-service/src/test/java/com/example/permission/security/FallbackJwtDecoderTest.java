package com.example.permission.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class FallbackJwtDecoderTest {

    @Test
    void fallsBackToSecondDecoderWhenPrimaryRejects() {
        Jwt expected = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("admin@example.com")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        JwtDecoder primary = token -> {
            throw new JwtException("primary rejected");
        };
        JwtDecoder secondary = token -> expected;

        FallbackJwtDecoder decoder = new FallbackJwtDecoder(List.of(primary, secondary));

        Jwt actual = decoder.decode("token");

        assertSame(expected, actual);
    }

    @Test
    void aggregatesFailuresWhenAllDecodersReject() {
        JwtDecoder first = token -> {
            throw new JwtException("first failure");
        };
        JwtDecoder second = token -> {
            throw new JwtException("second failure");
        };

        FallbackJwtDecoder decoder = new FallbackJwtDecoder(List.of(first, second));

        JwtException error = assertThrows(JwtException.class, () -> decoder.decode("token"));

        assertEquals("first failure", error.getMessage());
        assertEquals(1, error.getSuppressed().length);
        assertEquals("second failure", error.getSuppressed()[0].getMessage());
    }
}

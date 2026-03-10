package com.example.permission.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

    @Test
    void acceptsMatchingAudience() {
        AudienceValidator validator = new AudienceValidator(List.of("permission-service"), List.of("frontend"));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("123")
                .audience(List.of("permission-service"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertTrue(validator.validate(jwt).hasErrors() == false);
    }

    @Test
    void acceptsMatchingAuthorizedPartyWhenAudienceMissing() {
        AudienceValidator validator = new AudienceValidator(List.of("permission-service"), List.of("admin-cli"));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("123")
                .claim("azp", "admin-cli")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertTrue(validator.validate(jwt).hasErrors() == false);
    }

    @Test
    void rejectsTokenWhenNeitherAudienceNorAuthorizedPartyMatches() {
        AudienceValidator validator = new AudienceValidator(List.of("permission-service"), List.of("frontend"));
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("123")
                .claim("azp", "other-client")
                .audience(List.of("other-audience"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        assertFalse(validator.validate(jwt).hasErrors() == false);
    }
}

package com.example.transcript.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

class TranscriptInternalServiceJwtContractTest {

    private static final Instant NOW = Instant.now();

    @Test
    void validator_acceptsOnlyExactIssuerAudienceAndClient() {
        OAuth2TokenValidator<Jwt> validator = SecurityConfig.buildInternalServiceValidator(
                "auth-service", List.of("transcript-service"), List.of("meeting-ai"));

        assertThat(validator.validate(jwt(
                "auth-service", "transcript-service", "meeting-ai", "meeting-ai")).hasErrors())
                .isFalse();
        assertThat(validator.validate(jwt(
                "wrong-issuer", "transcript-service", "meeting-ai", "meeting-ai")).hasErrors())
                .isTrue();
        assertThat(validator.validate(jwt(
                "auth-service", "meeting-service", "meeting-ai", "meeting-ai")).hasErrors())
                .isTrue();
        assertThat(validator.validate(jwt(
                "auth-service", "transcript-service", "other-service", "other-service")).hasErrors())
                .isTrue();
        assertThat(validator.validate(jwt(
                "auth-service", "transcript-service", "meeting-ai", "other-service")).hasErrors())
                .isTrue();
    }

    @Test
    void converter_createsCanonicalAuthorityOnlyForTrustedServiceIssuer() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("transcript.internal.service-jwt.issuer", "auth-service");
        var converter = new SecurityConfig(environment).jwtAuthenticationConverter();

        var trusted = converter.convert(jwt(
                "auth-service", "transcript-service", "meeting-ai", "meeting-ai"));
        var keycloakLike = converter.convert(jwt(
                "https://keycloak.example/realms/platform",
                "transcript-service",
                "meeting-ai",
                "meeting-ai"));

        assertThat(trusted.getAuthorities())
                .contains(new SimpleGrantedAuthority(SecurityConfig.SVC_CANONICAL_READ));
        assertThat(keycloakLike.getAuthorities())
                .doesNotContain(new SimpleGrantedAuthority(SecurityConfig.SVC_CANONICAL_READ));
    }

    private static Jwt jwt(String issuer, String audience, String subject, String clientId) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .issuer(issuer)
                .audience(List.of(audience))
                .subject(subject)
                .claim("client_id", clientId)
                .claim("perm", List.of("transcript:canonical:read"))
                .issuedAt(NOW.minusSeconds(1))
                .expiresAt(NOW.plusSeconds(300))
                .build();
    }
}

package com.example.meeting.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class ExpectedServiceClientValidatorTest {

    @Test
    void matchingClientIdAndSubject_isAccepted() {
        OAuth2TokenValidatorResult result = validator("meeting-ai")
                .validate(jwt("meeting-ai", "meeting-ai"));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void unexpectedClientId_isRejected() {
        OAuth2TokenValidatorResult result = validator("meeting-ai")
                .validate(jwt("other-service", "other-service"));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void clientIdAndSubjectMismatch_isRejected() {
        OAuth2TokenValidatorResult result = validator("meeting-ai")
                .validate(jwt("meeting-ai", "other-service"));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void missingClientId_isRejected() {
        OAuth2TokenValidatorResult result = validator("meeting-ai")
                .validate(jwt(null, "meeting-ai"));

        assertThat(result.hasErrors()).isTrue();
    }

    @Test
    void emptyExpectedClientSet_failsClosed() {
        OAuth2TokenValidatorResult result = new ExpectedServiceClientValidator(List.of())
                .validate(jwt("meeting-ai", "meeting-ai"));

        assertThat(result.hasErrors()).isTrue();
    }

    private ExpectedServiceClientValidator validator(String... clientIds) {
        return new ExpectedServiceClientValidator(List.of(clientIds));
    }

    private Jwt jwt(String clientId, String subject) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .subject(subject);
        if (clientId != null) {
            builder.claim("client_id", clientId);
        }
        return builder.claims(claims -> claims.putAll(Map.of(
                        "iss", "auth-service",
                        "aud", List.of("meeting-service"))))
                .build();
    }
}

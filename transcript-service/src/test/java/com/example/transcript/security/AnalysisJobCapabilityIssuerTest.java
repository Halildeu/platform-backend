package com.example.transcript.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnalysisJobCapabilityIssuerTest {

    private static final byte[] SECRET =
            "transcript-capability-issuer-test-key-2026".getBytes(StandardCharsets.UTF_8);
    private static final String ENCODED_SECRET = Base64.getEncoder().encodeToString(SECRET);
    private static final Instant NOW = Instant.parse("2026-07-18T03:00:00Z");

    @Test
    void issue_signsExactShortLivedJobTuple() throws Exception {
        UUID tenantId = UUID.randomUUID();
        UUID meetingId = UUID.randomUUID();
        UUID sessionId = UUID.randomUUID();
        UUID analysisRunId = UUID.randomUUID();
        Instant finalizedAt = Instant.parse("2026-07-18T02:55:00Z");
        var issuer = issuer(ENCODED_SECRET);

        var issued = issuer.issue(new AnalysisJobCapabilityIssuer.JobBinding(
                tenantId,
                meetingId,
                sessionId,
                7L,
                finalizedAt,
                "a".repeat(64),
                analysisRunId,
                "analysis-v3"));

        SignedJWT jwt = SignedJWT.parse(issued.token());
        assertThat(jwt.getHeader().getAlgorithm()).isEqualTo(JWSAlgorithm.HS256);
        assertThat(jwt.verify(new MACVerifier(SECRET))).isTrue();
        var claims = jwt.getJWTClaimsSet();
        assertThat(claims.getIssuer()).isEqualTo("transcript-service");
        assertThat(claims.getAudience()).isEqualTo(List.of("meeting-service"));
        assertThat(claims.getSubject()).isEqualTo("meeting-ai");
        assertThat(claims.getStringClaim("client_id")).isEqualTo("meeting-ai");
        assertThat(claims.getStringClaim("perm")).isEqualTo("meeting:analysis-result:write");
        assertThat(claims.getJWTID()).isEqualTo(issued.capabilityId().toString());
        assertThat(claims.getStringClaim("tenant_id")).isEqualTo(tenantId.toString());
        assertThat(claims.getStringClaim("meeting_id")).isEqualTo(meetingId.toString());
        assertThat(claims.getStringClaim("session_id")).isEqualTo(sessionId.toString());
        assertThat(claims.getLongClaim("finalization_version")).isEqualTo(7L);
        assertThat(claims.getStringClaim("finalized_at")).isEqualTo(finalizedAt.toString());
        assertThat(claims.getStringClaim("transcript_sha256")).isEqualTo("a".repeat(64));
        assertThat(claims.getStringClaim("analysis_run_id")).isEqualTo(analysisRunId.toString());
        assertThat(claims.getStringClaim("analysis_spec_version")).isEqualTo("analysis-v3");
        assertThat(claims.getIssueTime().toInstant()).isEqualTo(NOW);
        assertThat(claims.getExpirationTime().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
        assertThat(issued.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(15)));
    }

    @Test
    void issue_withoutConfiguredSecret_failsClosed() {
        var issuer = issuer("");

        assertThatThrownBy(() -> issuer.issue(new AnalysisJobCapabilityIssuer.JobBinding(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        1L,
                        NOW,
                        "a".repeat(64),
                        UUID.randomUUID(),
                        "analysis-v1")))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getReason()).isEqualTo("JOB_CAPABILITY_UNAVAILABLE");
                });
    }

    private static AnalysisJobCapabilityIssuer issuer(String secret) {
        return new AnalysisJobCapabilityIssuer(
                secret,
                Duration.ofMinutes(15),
                "transcript-service",
                "meeting-service",
                "meeting-ai",
                Clock.fixed(NOW, ZoneOffset.UTC));
    }
}

package com.example.meeting.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.meeting.support.AnalysisJobCapabilityTestTokens;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnalysisJobCapabilityVerifierTest {

    private static final Instant NOW = Instant.parse("2026-07-18T03:00:00Z");
    private static final UUID CAPABILITY_ID = UUID.randomUUID();
    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID MEETING_ID = UUID.randomUUID();
    private static final UUID SESSION_ID = UUID.randomUUID();
    private static final UUID RUN_ID = UUID.randomUUID();
    private static final Instant FINALIZED_AT = Instant.parse("2026-07-18T02:55:00Z");

    @Test
    void verify_acceptsExactSignedTuple() {
        String token = token(CAPABILITY_ID, TENANT_ID, MEETING_ID, SESSION_ID, 4L,
                FINALIZED_AT, "a".repeat(64), RUN_ID, "analysis-v2",
                NOW.minusSeconds(1), NOW.plusSeconds(299));

        var binding = verifier(AnalysisJobCapabilityTestTokens.ENCODED_SECRET).verify(token);

        assertThat(binding.capabilityId()).isEqualTo(CAPABILITY_ID);
        assertThat(binding.tenantId()).isEqualTo(TENANT_ID);
        assertThat(binding.meetingId()).isEqualTo(MEETING_ID);
        assertThat(binding.sessionId()).isEqualTo(SESSION_ID);
        assertThat(binding.finalizationVersion()).isEqualTo(4L);
        assertThat(binding.finalizedAt()).isEqualTo(FINALIZED_AT);
        assertThat(binding.transcriptSha256()).isEqualTo("a".repeat(64));
        assertThat(binding.analysisRunId()).isEqualTo(RUN_ID);
        assertThat(binding.analysisSpecVersion()).isEqualTo("analysis-v2");
        assertThat(binding.expiresAt()).isEqualTo(NOW.plusSeconds(299));
    }

    @Test
    void verify_rejectsBadSignatureExpiredOversizedTtlAndMalformedTuple() {
        assertInvalid(verifier("YW5vdGhlci10ZXN0LWtleS10aGF0LWlzLWxvbmcgZW5vdWdoISE="), validToken());
        assertInvalid(verifier(AnalysisJobCapabilityTestTokens.ENCODED_SECRET),
                token(CAPABILITY_ID, TENANT_ID, MEETING_ID, SESSION_ID, 4L,
                        FINALIZED_AT, "a".repeat(64), RUN_ID, "analysis-v2",
                        NOW.minusSeconds(301), NOW));
        assertInvalid(verifier(AnalysisJobCapabilityTestTokens.ENCODED_SECRET),
                token(CAPABILITY_ID, TENANT_ID, MEETING_ID, SESSION_ID, 4L,
                        FINALIZED_AT, "a".repeat(64), RUN_ID, "analysis-v2",
                        NOW.minusSeconds(1), NOW.plus(Duration.ofMinutes(6))));
        assertInvalid(verifier(AnalysisJobCapabilityTestTokens.ENCODED_SECRET),
                token(CAPABILITY_ID, TENANT_ID, MEETING_ID, SESSION_ID, 0L,
                        FINALIZED_AT, "a".repeat(64), RUN_ID, "analysis-v2",
                        NOW.minusSeconds(1), NOW.plusSeconds(299)));
        assertInvalid(verifier(AnalysisJobCapabilityTestTokens.ENCODED_SECRET), "not-a-jwt");
    }

    @Test
    void verify_withoutConfiguredSecret_failsClosed() {
        assertThatThrownBy(() -> verifier("").verify(validToken()))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(503);
                    assertThat(ex.getReason()).isEqualTo("JOB_CAPABILITY_UNAVAILABLE");
                });
    }

    @Test
    void configuration_rejectsMaximumTtlAboveFiveMinutes() {
        assertThatThrownBy(() -> new AnalysisJobCapabilityVerifier(
                        AnalysisJobCapabilityTestTokens.ENCODED_SECRET,
                        "transcript-service",
                        "meeting-service",
                        "meeting-ai",
                        Duration.ofMinutes(5).plusSeconds(1),
                        Clock.fixed(NOW, ZoneOffset.UTC)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5 minutes");
    }

    private static String validToken() {
        return token(CAPABILITY_ID, TENANT_ID, MEETING_ID, SESSION_ID, 4L,
                FINALIZED_AT, "a".repeat(64), RUN_ID, "analysis-v2",
                NOW.minusSeconds(1), NOW.plusSeconds(299));
    }

    private static String token(
            UUID capabilityId,
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            long version,
            Instant finalizedAt,
            String hash,
            UUID runId,
            String spec,
            Instant issuedAt,
            Instant expiresAt) {
        return AnalysisJobCapabilityTestTokens.issue(
                capabilityId,
                tenantId,
                meetingId,
                sessionId,
                version,
                finalizedAt,
                hash,
                runId,
                spec,
                issuedAt,
                expiresAt);
    }

    private static AnalysisJobCapabilityVerifier verifier(String secret) {
        return new AnalysisJobCapabilityVerifier(
                secret,
                "transcript-service",
                "meeting-service",
                "meeting-ai",
                Duration.ofMinutes(5),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static void assertInvalid(AnalysisJobCapabilityVerifier verifier, String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(403);
                    assertThat(ex.getReason()).isEqualTo("JOB_CAPABILITY_INVALID");
                });
    }
}

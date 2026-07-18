package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class AnalysisGeneratedAtPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private final AnalysisGeneratedAtPolicy policy = new AnalysisGeneratedAtPolicy(
            Duration.ofMinutes(2), Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void acceptsInclusiveProvenanceBounds() {
        policy.validate(NOW.minusSeconds(60), NOW.minusSeconds(60));
        policy.validate(NOW.minusSeconds(60), NOW.plusSeconds(120));
    }

    @Test
    void rejectsGeneratedBeforeFinalizationAndBeyondServerSkewWithStableCode() {
        assertInvalid(NOW, NOW.minusMillis(1));
        assertInvalid(NOW, NOW.plusSeconds(121));
    }

    @Test
    void rejectsUnsafeConfiguration() {
        assertThatThrownBy(() -> new AnalysisGeneratedAtPolicy(
                Duration.ofMinutes(5).plusMillis(1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AnalysisGeneratedAtPolicy(
                Duration.ofMillis(-1), Clock.systemUTC()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private void assertInvalid(Instant finalizedAt, Instant generatedAt) {
        assertThatThrownBy(() -> policy.validate(finalizedAt, generatedAt))
                .isInstanceOfSatisfying(ResponseStatusException.class, ex -> {
                    assertThat(ex.getStatusCode().value()).isEqualTo(400);
                    assertThat(ex.getReason()).isEqualTo("ANALYSIS_GENERATED_AT_INVALID");
                });
    }
}

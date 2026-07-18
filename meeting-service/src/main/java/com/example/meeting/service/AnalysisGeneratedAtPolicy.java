package com.example.meeting.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Bounded provenance sanity check; never used for occurrence ordering. */
@Component
public class AnalysisGeneratedAtPolicy {

    private static final Duration HARD_MAX_SKEW = Duration.ofMinutes(5);

    private final Duration futureSkew;
    private final Clock clock;

    @Autowired
    public AnalysisGeneratedAtPolicy(
            @Value("${meeting.analysis-ingestion.generated-at-future-skew:PT2M}") Duration futureSkew) {
        this(futureSkew, Clock.systemUTC());
    }

    AnalysisGeneratedAtPolicy(Duration futureSkew, Clock clock) {
        if (futureSkew == null || futureSkew.isNegative() || futureSkew.compareTo(HARD_MAX_SKEW) > 0) {
            throw new IllegalArgumentException("analysis generated-at future skew must be between PT0S and PT5M");
        }
        this.futureSkew = futureSkew;
        this.clock = clock;
    }

    public void validate(Instant finalizedAt, Instant generatedAt) {
        Instant now = clock.instant();
        if (finalizedAt == null || generatedAt == null
                || !finalizedAt.equals(finalizedAt.truncatedTo(ChronoUnit.MICROS))
                || !generatedAt.equals(generatedAt.truncatedTo(ChronoUnit.MICROS))
                || generatedAt.isBefore(finalizedAt)
                || generatedAt.isAfter(now.plus(futureSkew))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ANALYSIS_GENERATED_AT_INVALID");
        }
    }
}

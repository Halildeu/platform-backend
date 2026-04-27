package com.example.permission.dataaccess;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Faz 21.3 PR-G — config for {@link OutboxPoller}. Spring binds via
 * {@code app.outbox.*} properties; defaults match the values written
 * into {@code application-integration.yml} so unit tests construct the
 * bean without a property source.
 *
 * <p>Codex 019dd0e0 iter-2 MAJOR fix — {@code @Validated} + per-field
 * constraints so misconfigured values fail boot rather than silently
 * misbehaving (e.g. {@code maxAttempts=0} would let every error become
 * a terminal FAILED on the first try; {@code initialBackoff} of zero
 * would tight-loop the retry cycle).
 */
@Component
@Validated
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxConfig {

    /** Used as the {@code @Scheduled fixedDelay} value via property reference. */
    @Min(1)
    private long pollIntervalMs = 5_000L;

    @Min(1)
    private int batchSize = 25;

    @NotNull
    private Duration initialBackoff = Duration.ofSeconds(5);

    @NotNull
    private Duration maxBackoff = Duration.ofMinutes(15);

    @Min(1)
    private int maxAttempts = 10;

    @NotNull
    private Duration processingLockTtl = Duration.ofMinutes(2);

    /**
     * Bean Validation's {@code @Positive} only supports numeric types, so
     * Duration fields are validated through this {@code @AssertTrue}
     * predicate after binding. The error mode (boolean false → constraint
     * violation) keeps misconfigurations from booting the application.
     */
    @AssertTrue(message = "app.outbox: initialBackoff, maxBackoff, and processingLockTtl must be strictly positive Durations")
    public boolean isDurationsPositive() {
        return isPositive(initialBackoff) && isPositive(maxBackoff) && isPositive(processingLockTtl);
    }

    @AssertTrue(message = "app.outbox.maxBackoff must be >= initialBackoff")
    public boolean isBackoffOrderingValid() {
        if (initialBackoff == null || maxBackoff == null) return false;
        return maxBackoff.compareTo(initialBackoff) >= 0;
    }

    private static boolean isPositive(Duration d) {
        return d != null && !d.isNegative() && !d.isZero();
    }

    public long getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(long pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public int getBatchSize() { return batchSize; }
    public void setBatchSize(int batchSize) { this.batchSize = batchSize; }

    public Duration getInitialBackoff() { return initialBackoff; }
    public void setInitialBackoff(Duration initialBackoff) { this.initialBackoff = initialBackoff; }

    public Duration getMaxBackoff() { return maxBackoff; }
    public void setMaxBackoff(Duration maxBackoff) { this.maxBackoff = maxBackoff; }

    public int getMaxAttempts() { return maxAttempts; }
    public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

    public Duration getProcessingLockTtl() { return processingLockTtl; }
    public void setProcessingLockTtl(Duration processingLockTtl) { this.processingLockTtl = processingLockTtl; }
}

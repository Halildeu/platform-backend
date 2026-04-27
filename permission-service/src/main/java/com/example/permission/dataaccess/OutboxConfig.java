package com.example.permission.dataaccess;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Faz 21.3 PR-G — config for {@link OutboxPoller}. Spring binds via
 * {@code app.outbox.*} properties; defaults match the values written
 * into {@code application-integration.yml} so unit tests construct the
 * bean without a property source.
 */
@Component
@ConfigurationProperties(prefix = "app.outbox")
public class OutboxConfig {

    /** Used as the {@code @Scheduled fixedDelay} value via property reference. */
    private long pollIntervalMs = 5_000L;
    private int batchSize = 25;
    private Duration initialBackoff = Duration.ofSeconds(5);
    private Duration maxBackoff = Duration.ofMinutes(15);
    private int maxAttempts = 10;
    private Duration processingLockTtl = Duration.ofMinutes(2);

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

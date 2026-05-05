package com.serban.notify.worker;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Exponential backoff calculator with jitter and cap (Codex 019dfa47 Q3 absorb).
 *
 * <p>Schedule: {@code initial × multiplier^(attempt-1)} with bounded random
 * jitter and absolute cap.
 *
 * <p>Example default config:
 * <ul>
 *   <li>initial = 30s</li>
 *   <li>multiplier = 2.5</li>
 *   <li>max-backoff = 1h</li>
 *   <li>jitter = ±25%</li>
 * </ul>
 *
 * <p>Schedule progression (no jitter, no cap):
 * <pre>
 *   attempt 1 fail → 30s
 *   attempt 2 fail → 75s
 *   attempt 3 fail → 187.5s (~3min)
 *   attempt 4 fail → 468.75s (~8min)
 *   attempt 5 fail → 1171.875s (~20min)
 * </pre>
 *
 * <p>Codex Q3 mandate: jitter avoids thundering herd; cap prevents excessive
 * tail latency in prod misconfiguration.
 */
@Component
public class BackoffCalculator {

    private final long initialMs;
    private final double multiplier;
    private final long maxBackoffMs;
    private final double jitterRatio;

    public BackoffCalculator(
        @Value("${notify.retry.backoff-initial-ms:30000}") long initialMs,
        @Value("${notify.retry.backoff-multiplier:2.5}") double multiplier,
        @Value("${notify.retry.max-backoff-ms:3600000}") long maxBackoffMs,
        @Value("${notify.retry.jitter-ratio:0.25}") double jitterRatio
    ) {
        this.initialMs = initialMs;
        this.multiplier = multiplier;
        this.maxBackoffMs = maxBackoffMs;
        this.jitterRatio = jitterRatio;
    }

    /**
     * Compute next-retry delay for a given attempt count.
     *
     * @param attempt 1-based attempt number that just failed (next try will
     *                wait this duration). attempt=1 → initial; attempt=N →
     *                initial × multiplier^(N-1) + jitter, capped at max.
     * @return Duration until next retry; never negative
     */
    public Duration computeDelay(int attempt) {
        if (attempt < 1) attempt = 1;
        double base = initialMs * Math.pow(multiplier, attempt - 1);
        double capped = Math.min(base, maxBackoffMs);
        long withJitter = applyJitter((long) capped);
        return Duration.ofMillis(Math.max(withJitter, 0));
    }

    private long applyJitter(long ms) {
        if (jitterRatio <= 0) return ms;
        double offset = ms * jitterRatio;
        long delta = (long) ThreadLocalRandom.current().nextDouble(-offset, offset);
        return ms + delta;
    }

    public long getInitialMs() { return initialMs; }
    public double getMultiplier() { return multiplier; }
    public long getMaxBackoffMs() { return maxBackoffMs; }
    public double getJitterRatio() { return jitterRatio; }
}

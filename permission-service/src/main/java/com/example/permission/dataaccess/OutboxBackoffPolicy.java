package com.example.permission.dataaccess;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Faz 21.3 PR-G — exponential backoff with jitter for {@link OutboxPoller}
 * retries (Codex 019dcf5c risk #6 — stuck recovery + retry semantics).
 *
 * <p>Schedule: {@code initialBackoff * 2^(attempt-1)} capped at
 * {@code maxBackoff}, multiplied by a random jitter in {@code [0.75, 1.25]}.
 * The jitter range avoids thundering-herd retry storms when many entries
 * fail at the same time (e.g. OpenFGA outage).
 */
@Component
public class OutboxBackoffPolicy {

    /**
     * Compute the next attempt timestamp.
     *
     * @param attemptCount  the just-incremented attempt counter (>= 1 on first retry)
     * @param initialBackoff base delay
     * @param maxBackoff    upper cap for the exponential schedule
     * @return {@code now + jitter * min(initialBackoff * 2^(attempt-1), maxBackoff)}
     */
    public Instant nextAttemptAt(int attemptCount, Duration initialBackoff, Duration maxBackoff) {
        long baseMillis = initialBackoff.toMillis();
        // Cap the exponent so a long-running pod with attempt_count > 60 cannot
        // overflow a long when shifting the base; 30 keeps headroom and is well
        // past any reasonable maxBackoff.
        int exponent = Math.min(Math.max(attemptCount - 1, 0), 30);
        long expMillis;
        try {
            expMillis = Math.multiplyExact(baseMillis, 1L << exponent);
        } catch (ArithmeticException overflow) {
            expMillis = Long.MAX_VALUE;
        }
        long capMillis = maxBackoff.toMillis();
        long durationMillis = Math.min(expMillis, capMillis);

        // Jitter [0.75, 1.25] — ±25% variance.
        double jitter = 0.75d + ThreadLocalRandom.current().nextDouble() * 0.5d;
        long jittered = Math.max(1L, (long) (durationMillis * jitter));

        return Instant.now().plusMillis(jittered);
    }
}

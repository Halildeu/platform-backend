package com.example.endpointadmin.tpmattest;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/** Faz 22.3B gate-4d — token-bucket rate limiter: burst capacity, throttle, and time-based refill. */
class TpmEnrollmentRateLimiterTest {

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        MutableClock(Instant s) { this.now = s; }
        void advanceMillis(long ms) { now = now.plusMillis(ms); }
        @Override public Instant instant() { return now; }
        @Override public long millis() { return now.toEpochMilli(); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId z) { return this; }
    }

    @Test
    void allowsUpToCapacityThenThrottles() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T00:00:00Z"));
        TpmEnrollmentRateLimiter rl = new TpmEnrollmentRateLimiter(clock);
        for (int i = 0; i < TpmEnrollmentRateLimiter.CAPACITY; i++) {
            assertThat(rl.allow("k")).as("burst token %d", i).isTrue();
        }
        assertThat(rl.allow("k")).as("bucket empty → throttle").isFalse();
    }

    @Test
    void refillsOverTime() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T00:00:00Z"));
        TpmEnrollmentRateLimiter rl = new TpmEnrollmentRateLimiter(clock);
        for (int i = 0; i < TpmEnrollmentRateLimiter.CAPACITY; i++) rl.allow("k");
        assertThat(rl.allow("k")).isFalse();
        clock.advanceMillis(1100); // > 1s → ≥1 token refilled at 1/sec
        assertThat(rl.allow("k")).as("a token refilled after ~1s").isTrue();
    }

    @Test
    void keysAreIndependent() {
        MutableClock clock = new MutableClock(Instant.parse("2026-06-15T00:00:00Z"));
        TpmEnrollmentRateLimiter rl = new TpmEnrollmentRateLimiter(clock);
        for (int i = 0; i < TpmEnrollmentRateLimiter.CAPACITY; i++) rl.allow("a");
        assertThat(rl.allow("a")).isFalse();
        assertThat(rl.allow("b")).as("a different key has its own bucket").isTrue();
    }

    @Test
    void nullKeyDenied() {
        TpmEnrollmentRateLimiter rl = new TpmEnrollmentRateLimiter(
                new MutableClock(Instant.parse("2026-06-15T00:00:00Z")));
        assertThat(rl.allow(null)).isFalse();
    }
}

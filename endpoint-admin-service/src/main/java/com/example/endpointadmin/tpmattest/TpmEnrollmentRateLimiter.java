package com.example.endpointadmin.tpmattest;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.3B (ADR-0039) gate-4d — a small in-memory token-bucket rate limiter for the TPM
 * enrollment endpoints (volumetric anti-DoS; design §9 + Codex {@code 019ec723} gate-4d MUST#2
 * — bound the cost of failed-attest / nonce floods). Keyed per-IP and per-scope. Node-local
 * (single-replica default, matching {@link InMemoryTpmNonceStore}); a distributed limiter is the
 * same multi-replica gate. Default-off feature, so this only meters when enrollment is enabled.
 */
@Component
public class TpmEnrollmentRateLimiter {

    /** Bucket capacity (max burst) and refill rate (tokens/second) — conservative anti-DoS defaults. */
    static final int CAPACITY = 20;
    static final double REFILL_PER_SEC = 1.0;

    private static final class Bucket {
        double tokens;
        long lastRefillMillis;
        Bucket(double tokens, long now) { this.tokens = tokens; this.lastRefillMillis = now; }
    }

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final Clock clock;

    public TpmEnrollmentRateLimiter(Clock clock) {
        this.clock = clock;
    }

    /** @return true if a token was available (request allowed); false if the bucket is empty (throttle). */
    public boolean allow(String key) {
        if (key == null) {
            return false;
        }
        long now = clock.millis();
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(CAPACITY, now));
        synchronized (b) {
            double elapsedSec = Math.max(0, now - b.lastRefillMillis) / 1000.0;
            b.tokens = Math.min(CAPACITY, b.tokens + elapsedSec * REFILL_PER_SEC);
            b.lastRefillMillis = now;
            if (b.tokens >= 1.0) {
                b.tokens -= 1.0;
                return true;
            }
            return false;
        }
    }

    /** Best-effort eviction of idle buckets (full again) to bound memory. */
    public void evictIdle() {
        long now = clock.millis();
        buckets.values().removeIf(b -> {
            synchronized (b) {
                double elapsedSec = Math.max(0, now - b.lastRefillMillis) / 1000.0;
                return (b.tokens + elapsedSec * REFILL_PER_SEC) >= CAPACITY;
            }
        });
    }

    int bucketCount() {
        return buckets.size();
    }
}

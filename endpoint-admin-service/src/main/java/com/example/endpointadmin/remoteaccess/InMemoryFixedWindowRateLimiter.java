package com.example.endpointadmin.remoteaccess;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory fixed-window {@link RemoteAccessRateLimiter} — DEV/TEST reference (single-process; prod uses
 * a distributed limiter). Each axis has its own per-key counter that resets every {@code windowMillis};
 * an attempt is {@code ALLOWED} only if all three axes are under their limit. The clock is injected so
 * tests are deterministic.
 *
 * <p><b>Check-then-commit (Codex 019eb54b absorb):</b> the three axes are PRE-CHECKED first; counters are
 * incremented ONLY when all three are under their limit. A denied attempt does NOT burn the other axes'
 * budgets, so a flood on one axis can't self-DoS legit traffic on another. The first tripped axis is
 * reported (operator → network → session); it is internal/audit-only (client sees a uniform throttle).
 */
public final class InMemoryFixedWindowRateLimiter implements RemoteAccessRateLimiter {

    private final long windowMillis;
    private final int operatorLimit;
    private final int networkLimit;
    private final int sessionLimit;
    private final LongSupplier clock;

    private final ConcurrentHashMap<String, Window> operator = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> network = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Window> session = new ConcurrentHashMap<>();

    public InMemoryFixedWindowRateLimiter(long windowMillis, int operatorLimit, int networkLimit,
                                          int sessionLimit, LongSupplier clock) {
        this.windowMillis = windowMillis;
        this.operatorLimit = operatorLimit;
        this.networkLimit = networkLimit;
        this.sessionLimit = sessionLimit;
        this.clock = clock;
    }

    @Override
    public RateLimitDecision tryAcquire(String operatorUserId, String sourceNetwork, String sessionDeviceKey) {
        long now = clock.getAsLong();
        Window wOp = operator.computeIfAbsent(key(operatorUserId), k -> new Window());
        Window wNet = network.computeIfAbsent(key(sourceNetwork), k -> new Window());
        Window wSess = session.computeIfAbsent(key(sessionDeviceKey), k -> new Window());

        // PRE-CHECK each axis without mutating; deny (no commit) if any is already at its limit.
        if (wOp.peek(now, windowMillis) >= operatorLimit) {
            return RateLimitDecision.THROTTLED_OPERATOR;
        }
        if (wNet.peek(now, windowMillis) >= networkLimit) {
            return RateLimitDecision.THROTTLED_NETWORK;
        }
        if (wSess.peek(now, windowMillis) >= sessionLimit) {
            return RateLimitDecision.THROTTLED_SESSION;
        }
        // all under → commit all three.
        wOp.commit(now, windowMillis);
        wNet.commit(now, windowMillis);
        wSess.commit(now, windowMillis);
        return RateLimitDecision.ALLOWED;
    }

    private static String key(String raw) {
        return raw == null || raw.isBlank() ? "<none>" : raw;
    }

    /** A per-key fixed window: count resets when the window rolls over. peek = read; commit = increment. */
    private static final class Window {
        private long windowStart = Long.MIN_VALUE;
        private long count;

        private void rollIfNeeded(long now, long windowMillis) {
            if (windowStart == Long.MIN_VALUE || now - windowStart >= windowMillis) {
                windowStart = now;
                count = 0;
            }
        }

        synchronized long peek(long now, long windowMillis) {
            rollIfNeeded(now, windowMillis);
            return count;
        }

        synchronized void commit(long now, long windowMillis) {
            rollIfNeeded(now, windowMillis);
            count++;
        }
    }
}

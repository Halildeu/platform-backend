package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Faz 22.6 B2 — 3-axis rate limiter tests (Codex 019eb54b criterion #9). Deterministic via injected clock.
 */
class RemoteAccessRateLimiterTest {

    private final AtomicLong clock = new AtomicLong(1_000L);

    private RemoteAccessRateLimiter limiter(int opLimit, int netLimit, int sessLimit, long window) {
        return new InMemoryFixedWindowRateLimiter(window, opLimit, netLimit, sessLimit, clock::get);
    }

    @Test
    void allowedWhileUnderEveryAxisThreshold() {
        var rl = limiter(5, 5, 5, 60_000);
        for (int i = 0; i < 5; i++) {
            assertEquals(RemoteAccessRateLimiter.RateLimitDecision.ALLOWED,
                    rl.tryAcquire("op", "net", "sess"));
        }
    }

    @Test
    void operatorAxisThrottlesEvenWithFreshNetworkAndSession() {
        var rl = limiter(2, 100, 100, 60_000);
        rl.tryAcquire("op", "n1", "s1");
        rl.tryAcquire("op", "n2", "s2");
        // 3rd op attempt exceeds operatorLimit=2 even though network/session are fresh
        assertEquals(RemoteAccessRateLimiter.RateLimitDecision.THROTTLED_OPERATOR,
                rl.tryAcquire("op", "n3", "s3"));
    }

    @Test
    void networkAxisThrottlesAcrossDifferentOperators() {
        var rl = limiter(100, 2, 100, 60_000);
        rl.tryAcquire("opA", "shared-net", "s1");
        rl.tryAcquire("opB", "shared-net", "s2");
        assertEquals(RemoteAccessRateLimiter.RateLimitDecision.THROTTLED_NETWORK,
                rl.tryAcquire("opC", "shared-net", "s3"));
    }

    @Test
    void sessionAxisThrottles() {
        var rl = limiter(100, 100, 2, 60_000);
        rl.tryAcquire("o1", "nA", "dev-1");
        rl.tryAcquire("o2", "nB", "dev-1");
        assertEquals(RemoteAccessRateLimiter.RateLimitDecision.THROTTLED_SESSION,
                rl.tryAcquire("o3", "nC", "dev-1"));
    }

    @Test
    void windowResetRestoresAllowance() {
        var rl = limiter(1, 100, 100, 10_000);
        assertEquals(RemoteAccessRateLimiter.RateLimitDecision.ALLOWED, rl.tryAcquire("op", "n", "s"));
        assertEquals(RemoteAccessRateLimiter.RateLimitDecision.THROTTLED_OPERATOR, rl.tryAcquire("op", "n", "s"));
        clock.addAndGet(10_001); // roll past the window
        assertEquals(RemoteAccessRateLimiter.RateLimitDecision.ALLOWED, rl.tryAcquire("op", "n", "s"));
    }

    @Test
    void deniedAttemptDoesNotBurnOtherAxesBudgets() {
        // Codex absorb (check-then-commit): an operator-throttled flood must NOT consume the shared
        // network/session budget, so legit traffic on a fresh operator + the same network still passes.
        var rl = limiter(1, 5, 5, 60_000);
        assertEquals(RemoteAccessRateLimiter.RateLimitDecision.ALLOWED,
                rl.tryAcquire("badOp", "shared-net", "shared-dev")); // op count → 1
        // 3 more attempts by badOp are operator-throttled; they must not increment network/session
        for (int i = 0; i < 3; i++) {
            assertEquals(RemoteAccessRateLimiter.RateLimitDecision.THROTTLED_OPERATOR,
                    rl.tryAcquire("badOp", "shared-net", "shared-dev"));
        }
        // network has only 1 committed hit so far → a different operator on the same network is still ALLOWED
        for (int i = 0; i < 4; i++) {
            assertEquals(RemoteAccessRateLimiter.RateLimitDecision.ALLOWED,
                    rl.tryAcquire("goodOp" + i, "shared-net", "dev" + i));
        }
    }
}

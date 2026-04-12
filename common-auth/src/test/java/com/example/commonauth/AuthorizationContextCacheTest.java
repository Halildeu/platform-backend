package com.example.commonauth;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AuthorizationContextCache — TTL expiration, supplier pattern.
 * SK-7 coverage target.
 */
class AuthorizationContextCacheTest {

    @Test
    void get_firstCall_invokesSupplier() {
        var cache = new AuthorizationContextCache(java.time.Duration.ofSeconds(60));
        var callCount = new AtomicInteger(0);
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of());

        var result = cache.get("user1", () -> {
            callCount.incrementAndGet();
            return ctx;
        });
        assertEquals(ctx, result);
        assertEquals(1, callCount.get());
    }

    @Test
    void get_withinTtl_returnsCached() {
        var cache = new AuthorizationContextCache(java.time.Duration.ofSeconds(60));
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of());
        var callCount = new AtomicInteger(0);

        cache.get("user1", () -> { callCount.incrementAndGet(); return ctx; });
        cache.get("user1", () -> { callCount.incrementAndGet(); return ctx; });

        assertEquals(1, callCount.get());
    }

    @Test
    void get_afterTtl_invokesSupplierAgain() throws InterruptedException {
        var cache = new AuthorizationContextCache(java.time.Duration.ofMillis(50)); // 50ms TTL
        var ctx = AuthorizationContext.of(1L, "a@b.com", Set.of(), Set.of());
        var callCount = new AtomicInteger(0);

        cache.get("user1", () -> { callCount.incrementAndGet(); return ctx; });
        Thread.sleep(100);
        cache.get("user1", () -> { callCount.incrementAndGet(); return ctx; });

        assertEquals(2, callCount.get());
    }

    @Test
    void get_differentKeys_separateEntries() {
        var cache = new AuthorizationContextCache(java.time.Duration.ofSeconds(60));
        var ctx1 = AuthorizationContext.of(1L, "a@b.com", Set.of("A"), Set.of());
        var ctx2 = AuthorizationContext.of(2L, "b@b.com", Set.of("B"), Set.of());

        var result1 = cache.get("user1", () -> ctx1);
        var result2 = cache.get("user2", () -> ctx2);

        assertEquals(1L, result1.getUserId());
        assertEquals(2L, result2.getUserId());
    }
}

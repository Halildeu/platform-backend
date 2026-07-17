package com.example.commonauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * board #2556 — revocation must take effect at once.
 *
 * <p>Measured on k3d-test 2026-07-17: an admin revoked a scope through the product API — the scope
 * row went {@code REVOKED}, the outbox reached {@code PROCESSED}, the OpenFGA tuple was deleted and
 * {@code /authz/me} dropped the scope immediately — yet the product endpoint kept answering
 * {@code 200} for ~271 seconds, because the cache keyed a positive decision by subject alone and
 * held it for a fixed 5-minute TTL. These tests pin the invariant against a fake clock, so the next
 * regression is caught in CI rather than by someone sitting on curl for five minutes.
 *
 * <p>The old TTL-only {@code get(key, supplier)} API is gone on purpose: it could not express
 * "still valid?", so every caller had to be re-checked at compile time rather than silently keeping
 * the stale-grant behaviour.
 */
class AuthorizationContextCacheTest {

    private static final String KEY = AuthorizationContextCache.key(
            "https://testai.acik.com/realms/platform-test", "c9ee4f46-1f67-41d6-a2c4-031d7fd7e2a8", 1L);

    /** A clock the test moves by hand; nothing here waits on wall time. */
    private static final class TestClock extends Clock {
        private Instant now = Instant.parse("2026-07-17T00:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration d) { now = now.plus(d); }
    }

    private static AuthorizationContext granting() {
        return AuthorizationContext.of(7L, "p@example.com", Set.of("VARIANT_SCOPE_CANARY"),
                Set.of("VARIANTS_READ"), Set.of(), Set.of(8001L), Set.of());
    }

    private static AuthorizationContext revoked() {
        return AuthorizationContext.of(7L, "p@example.com", Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @Test
    @DisplayName("THE regression: revoke is visible on the very next call — no TTL wait")
    void revokeTakesEffectImmediately() {
        TestClock clock = new TestClock();
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), clock);
        AtomicLong revision = new AtomicLong(127);
        AtomicReference<AuthorizationContext> upstream = new AtomicReference<>(granting());

        AuthorizationContext before = cache.get(KEY, revision::get, upstream::get);
        assertTrue(before.getAllowedProjectIds().contains(8001L), "warm the cache with a positive decision");

        // Admin revokes: upstream drops the grant, the outbox bumps the revision.
        upstream.set(revoked());
        revision.set(129);

        AuthorizationContext after = cache.get(KEY, revision::get, upstream::get);

        assertTrue(after.grantsNothing(),
                "revocation must be effective on the next request — the ~271s window measured on "
                        + "k3d-test is exactly what this asserts against");
        assertEquals(Instant.parse("2026-07-17T00:00:00Z"), clock.instant(),
                "not one tick passed, so the TTL cannot be what closed it");
    }

    @Test
    @DisplayName("a fresh grant is visible at once too (positive decisions are revision-bound as well)")
    void grantTakesEffectImmediately() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        AtomicLong revision = new AtomicLong(1);
        AtomicReference<AuthorizationContext> upstream = new AtomicReference<>(revoked());

        assertTrue(cache.get(KEY, revision::get, upstream::get).grantsNothing());

        upstream.set(granting());
        revision.set(2);

        assertTrue(cache.get(KEY, revision::get, upstream::get).getAllowedProjectIds().contains(8001L),
                "a cached deny must not outlive the grant that replaces it");
    }

    @Test
    @DisplayName("unchanged revision reuses the cached decision (it is still a cache)")
    void unchangedRevisionIsServedFromCache() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        AtomicInteger loads = new AtomicInteger();

        for (int i = 0; i < 5; i++) {
            cache.get(KEY, () -> 127L, () -> { loads.incrementAndGet(); return granting(); });
        }

        assertEquals(1, loads.get(), "while the revision holds, upstream must not be re-asked per request");
    }

    @Test
    @DisplayName("SECURITY: revision unreadable → a cached GRANT is refused, never reused")
    void unreadableRevisionRefusesCachedGrant() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        cache.get(KEY, () -> 127L, AuthorizationContextCacheTest::granting);

        assertThrows(AuthorizationContextCache.RevisionUnavailableException.class,
                () -> cache.get(KEY,
                        () -> { throw new IllegalStateException("permission-service down"); },
                        AuthorizationContextCacheTest::granting),
                "an outage must not promote a stale allow into an answer");
    }

    @Test
    @DisplayName("revision unreadable → a cached DENY is still served (an outage must not lock people out)")
    void unreadableRevisionKeepsCachedDeny() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        cache.get(KEY, () -> 127L, AuthorizationContextCacheTest::revoked);

        AuthorizationContext ctx = cache.get(KEY,
                () -> { throw new IllegalStateException("permission-service down"); },
                AuthorizationContextCacheTest::granting);

        assertTrue(ctx.grantsNothing(), "continuing to deny cannot widen access, so it is safe to reuse");
    }

    @Test
    @DisplayName("revision unreadable with nothing cached → refuse (no blind allow)")
    void unreadableRevisionWithEmptyCacheRefuses() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());

        assertThrows(AuthorizationContextCache.RevisionUnavailableException.class,
                () -> cache.get(KEY, () -> { throw new IllegalStateException("down"); },
                        AuthorizationContextCacheTest::granting));
    }

    @Test
    @DisplayName("TTL only bounds memory — it re-derives, it does not decide")
    void ttlOnlyEvicts() {
        TestClock clock = new TestClock();
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), clock);
        AtomicInteger loads = new AtomicInteger();

        cache.get(KEY, () -> 127L, () -> { loads.incrementAndGet(); return granting(); });
        clock.advance(Duration.ofMinutes(6));
        AuthorizationContext ctx = cache.get(KEY, () -> 127L, () -> { loads.incrementAndGet(); return granting(); });

        assertEquals(2, loads.get(), "an expired entry is re-derived");
        assertTrue(ctx.getAllowedProjectIds().contains(8001L), "same revision ⇒ same answer, merely recomputed");
    }

    @Test
    @DisplayName("entries are replaced, not accumulated per revision (subject:revision keys would leak)")
    void revisionChurnDoesNotGrowTheCache() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());

        for (long rev = 1; rev <= 50; rev++) {
            long r = rev;
            cache.get(KEY, () -> r, AuthorizationContextCacheTest::granting);
        }

        assertEquals(1, cache.size(), "one principal must occupy exactly one entry");
    }

    @Test
    @DisplayName("principals are separated by issuer and tenant, not by subject alone")
    void keySeparatesIssuerAndTenant() {
        String sub = "same-subject";
        assertNotEquals(AuthorizationContextCache.key("https://a", sub, 1L),
                AuthorizationContextCache.key("https://b", sub, 1L),
                "two issuers can mint the same sub");
        assertNotEquals(AuthorizationContextCache.key("https://a", sub, 1L),
                AuthorizationContextCache.key("https://a", sub, 2L),
                "the same human can hold different authority per tenant");

        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        cache.get(AuthorizationContextCache.key("https://a", sub, 1L), () -> 1L,
                AuthorizationContextCacheTest::granting);
        AuthorizationContext other = cache.get(AuthorizationContextCache.key("https://a", sub, 2L), () -> 1L,
                AuthorizationContextCacheTest::revoked);

        assertTrue(other.grantsNothing(), "tenant 2 must not inherit tenant 1's grant");
    }

    @Test
    @DisplayName("SECURITY: key encoding is injective — no two principals can collide")
    void keyEncodingIsInjective() {
        // The exact collision a delimiter-joined key produces: with a space separator both of these
        // render as "a b c". Two principals sharing an entry means one is served the other's authority.
        assertNotEquals(AuthorizationContextCache.key("a b", "c", null),
                AuthorizationContextCache.key("a", "b c", null),
                "a separator that can occur inside an issuer or subject is not a safe encoding");

        // Same class of ambiguity on the tenant boundary, and for the ':' used by the length prefix.
        assertNotEquals(AuthorizationContextCache.key("i", "s", "1 2"),
                AuthorizationContextCache.key("i", "s 1", "2"));
        assertNotEquals(AuthorizationContextCache.key("2:xx", "y", null),
                AuthorizationContextCache.key("2", "xxy", null));

        // null and "" are different principals, not the same one.
        assertNotEquals(AuthorizationContextCache.key(null, "s", null),
                AuthorizationContextCache.key("", "s", null));
        assertNotEquals(AuthorizationContextCache.key("i", "s", null),
                AuthorizationContextCache.key("i", "s", ""));

        // Equal inputs must still produce equal keys, or nothing would ever cache.
        assertEquals(AuthorizationContextCache.key("https://i", "sub", 1L),
                AuthorizationContextCache.key("https://i", "sub", 1L));
    }

    @Test
    @DisplayName("a global revision bump single-flights the refresh instead of stampeding upstream")
    void concurrentRefreshIsSingleFlighted() throws Exception {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        AtomicInteger loads = new AtomicInteger();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return cache.get(KEY, () -> 200L, () -> {
                        loads.incrementAndGet();
                        return granting();
                    });
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(10, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(1, loads.get(),
                "requests racing the same revision bump must not each hit permission-service");
    }

    @Test
    @DisplayName("SECURITY: a null answer from upstream is refused, not cached as a deny")
    void nullContextIsRefused() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());

        assertThrows(AuthorizationContextCache.RevisionUnavailableException.class,
                () -> cache.get(KEY, () -> 1L, () -> null),
                "a non-answer is not a decision; caching it as 'grants nothing' would look like a "
                        + "deny and would then be served during an outage as if it had been derived");
        assertEquals(0, cache.size(), "and nothing is left behind to be served later");
    }

    @Test
    @DisplayName("grantsNothing() ignores identity: knowing who you are is not authority")
    void identityIsNotAuthority() {
        assertTrue(AuthorizationContext.of(7L, "p@example.com", Set.of(), Set.of(), Set.of(), Set.of(), Set.of())
                .grantsNothing());
        assertFalse(AuthorizationContext.of(null, null, Set.of(), Set.of(), Set.of(), Set.of(8001L), Set.of())
                .grantsNothing(), "a project scope is authority even without identity fields");
    }

    @Test
    @DisplayName("different principals keep separate entries")
    void differentPrincipalsAreSeparate() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        String a = AuthorizationContextCache.key("https://i", "sub-a", 1L);
        String b = AuthorizationContextCache.key("https://i", "sub-b", 1L);

        assertEquals(7L, cache.get(a, () -> 1L, AuthorizationContextCacheTest::granting).getUserId());
        assertTrue(cache.get(b, () -> 1L, AuthorizationContextCacheTest::revoked).grantsNothing());
        assertEquals(2, cache.size());
    }

    @Test
    @DisplayName("evict drops an entry; correctness never depended on it being called")
    void evictIsAnOptimisation() {
        var cache = new AuthorizationContextCache(Duration.ofMinutes(5), new TestClock());
        cache.get(KEY, () -> 1L, AuthorizationContextCacheTest::granting);
        cache.evict(KEY);
        assertEquals(0, cache.size());

        assertNotNull(cache.get(KEY, () -> 1L, AuthorizationContextCacheTest::granting),
                "a dropped entry is simply re-derived on the next call");
    }
}

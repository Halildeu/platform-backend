package com.example.commonauth.scope;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ScopeContextCacheTest {

    private static ScopeContext sample(String userId) {
        return new ScopeContext(userId, Set.of(1L), Set.of(2L), Set.of(3L), Set.of(), false);
    }

    @Nested @DisplayName("Enabled")
    class Enabled {
        private final ScopeContextCache cache = new ScopeContextCache(
                Duration.ofSeconds(30), Duration.ZERO, 100, true);

        @Test void missReturnsNull() { assertNull(cache.get("x:v1:s0:m0")); }

        @Test void putThenGet() {
            String key = ScopeContextCache.cacheKey("10", 1L, "s", "m");
            cache.put(key, sample("10"));
            assertNotNull(cache.get(key));
            assertEquals("10", cache.get(key).userId());
        }

        @Test void differentVersionMiss() {
            cache.put(ScopeContextCache.cacheKey("10", 1L, "s", "m"), sample("10"));
            assertNull(cache.get(ScopeContextCache.cacheKey("10", 2L, "s", "m")));
        }

        @Test void differentStoreIdMiss() {
            cache.put(ScopeContextCache.cacheKey("10", 1L, "A", "m"), sample("10"));
            assertNull(cache.get(ScopeContextCache.cacheKey("10", 1L, "B", "m")));
        }

        @Test void evictUserOnlyTarget() {
            cache.put(ScopeContextCache.cacheKey("10", 1L, "s", "m"), sample("10"));
            cache.put(ScopeContextCache.cacheKey("20", 1L, "s", "m"), sample("20"));
            cache.evictUser("10");
            assertNull(cache.get(ScopeContextCache.cacheKey("10", 1L, "s", "m")));
            assertNotNull(cache.get(ScopeContextCache.cacheKey("20", 1L, "s", "m")));
        }

        @Test void evictAllClearsAll() {
            cache.put(ScopeContextCache.cacheKey("10", 1L, "s", "m"), sample("10"));
            cache.put(ScopeContextCache.cacheKey("20", 1L, "s", "m"), sample("20"));
            cache.evictAll();
            assertNull(cache.get(ScopeContextCache.cacheKey("10", 1L, "s", "m")));
            assertNull(cache.get(ScopeContextCache.cacheKey("20", 1L, "s", "m")));
        }

        @Test void nullStoreModelFallback() {
            assertEquals("42:v1:s0:m0", ScopeContextCache.cacheKey("42", 1L, null, null));
        }

        @Test void statsRecorded() {
            cache.get("miss:v1:s0:m0");
            cache.put("hit:v1:s0:m0", sample("hit"));
            cache.get("hit:v1:s0:m0");
            assertTrue(cache.stats().missCount() >= 1);
            assertTrue(cache.stats().hitCount() >= 1);
        }
    }

    @Nested @DisplayName("Disabled")
    class Disabled {
        private final ScopeContextCache cache = new ScopeContextCache(Duration.ofSeconds(30), 100, false);

        @Test void getReturnsNull() { assertNull(cache.get("any:v1:s0:m0")); }
        @Test void putIsNoop() {
            cache.put("k:v1:s0:m0", sample("1"));
            assertNull(cache.get("k:v1:s0:m0"));
        }
        @Test void isEnabledFalse() { assertFalse(cache.isEnabled()); }
    }
}

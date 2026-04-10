package com.example.commonauth.scope;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.stats.CacheStats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Caffeine cache for ScopeContext — replaces 5 OpenFGA calls per request with single lookup.
 * Cache key: userId:v{version}:s{storeId}:m{modelId}
 */
public class ScopeContextCache {

    private static final Logger log = LoggerFactory.getLogger(ScopeContextCache.class);
    private final Cache<String, ScopeContext> cache;
    private final boolean enabled;

    public ScopeContextCache(Duration ttl, long maxSize, boolean enabled) {
        this(ttl, Duration.ofSeconds(3), maxSize, enabled);
    }

    public ScopeContextCache(Duration ttl, Duration jitter, long maxSize, boolean enabled) {
        this.enabled = enabled;
        if (enabled) {
            long jitterMs = jitter.toMillis();
            long effectiveTtlMs = ttl.toMillis() + ThreadLocalRandom.current().nextLong(-jitterMs, jitterMs + 1);
            this.cache = Caffeine.newBuilder()
                    .expireAfterWrite(Duration.ofMillis(Math.max(effectiveTtlMs, 1000)))
                    .maximumSize(maxSize)
                    .recordStats()
                    .build();
            log.info("ScopeContextCache initialized: ttl={}ms, maxSize={}", effectiveTtlMs, maxSize);
        } else {
            this.cache = null;
            log.info("ScopeContextCache disabled");
        }
    }

    public static String cacheKey(String userId, long authzVersion, String storeId, String modelId) {
        return userId + ":v" + authzVersion + ":s" + (storeId != null ? storeId : "0") + ":m" + (modelId != null ? modelId : "0");
    }

    public ScopeContext get(String key) {
        if (!enabled || cache == null) return null;
        return cache.getIfPresent(key);
    }

    public void put(String key, ScopeContext context) {
        if (!enabled || cache == null || context == null) return;
        cache.put(key, context);
    }

    public void evictUser(String userId) {
        if (!enabled || cache == null) return;
        String prefix = userId + ":";
        cache.asMap().keySet().removeIf(k -> k.startsWith(prefix));
    }

    public void evictAll() {
        if (!enabled || cache == null) return;
        cache.invalidateAll();
    }

    public CacheStats stats() {
        return cache != null ? cache.stats() : CacheStats.empty();
    }

    public boolean isEnabled() { return enabled; }
}

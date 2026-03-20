package com.example.commonauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Basit TTL'li AuthorizationContext cache'i.
 */
public class AuthorizationContextCache {

    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public AuthorizationContextCache(Duration ttl) {
        this(ttl, Clock.systemUTC());
    }

    public AuthorizationContextCache(Duration ttl, Clock clock) {
        this.ttl = ttl == null ? Duration.ofMinutes(5) : ttl;
        this.clock = clock == null ? Clock.systemUTC() : clock;
    }

    public AuthorizationContext get(String key, Supplier<AuthorizationContext> supplier) {
        Objects.requireNonNull(key, "cache key required");
        AuthorizationContext cached = getIfPresent(key);
        if (cached != null) {
            return cached;
        }
        AuthorizationContext ctx = Optional.ofNullable(supplier.get()).orElse(AuthorizationContext.of(null, null, null, null, null, null, null));
        put(key, ctx);
        return ctx;
    }

    public AuthorizationContext getIfPresent(String key) {
        Objects.requireNonNull(key, "cache key required");
        Instant now = clock.instant();
        Cached existing = cache.get(key);
        if (existing == null) {
            return null;
        }
        if (existing.expiresAt().isAfter(now)) {
            return existing.ctx();
        }
        cache.remove(key, existing);
        return null;
    }

    public void put(String key, AuthorizationContext ctx) {
        Objects.requireNonNull(key, "cache key required");
        AuthorizationContext safeContext =
                Optional.ofNullable(ctx).orElse(AuthorizationContext.of(null, null, null, null, null, null, null));
        cache.put(key, new Cached(safeContext, clock.instant().plus(ttl)));
    }

    public void clear() {
        cache.clear();
    }

    private record Cached(AuthorizationContext ctx, Instant expiresAt) {
    }
}

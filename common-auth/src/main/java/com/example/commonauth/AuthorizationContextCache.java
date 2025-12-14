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
        Instant now = clock.instant();
        Cached existing = cache.get(key);
        if (existing != null && existing.expiresAt().isAfter(now)) {
            return existing.ctx();
        }
        AuthorizationContext ctx = Optional.ofNullable(supplier.get()).orElse(AuthorizationContext.of(null, null, null, null, null, null, null));
        cache.put(key, new Cached(ctx, now.plus(ttl)));
        return ctx;
    }

    public void clear() {
        cache.clear();
    }

    private record Cached(AuthorizationContext ctx, Instant expiresAt) {
    }
}

package com.example.gpcore.authz;

import com.example.gpcore.domain.AuthorizationDecision;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * Short-TTL, version-aware decision cache for bounded bulk authorization
 * (ADR-0035 §6). Correctness — not the common-auth inner cache — lives HERE: the
 * key ({@link AuthzCacheKey}) carries {@code policyVersion} + {@code tupleRevision}
 * + {@code subjectPolicyVersion}, so a positive can never be served after any of
 * them changes. Deny-by-default: the loader converts every failure to a deny, so
 * nothing un-decided is ever cached as an allow.
 *
 * <p>A zero/negative TTL truly disables caching (no Caffeine instance is built) —
 * unlike the common-auth bug this code intentionally avoids.
 */
public class DecisionCache {

    private final Cache<AuthzCacheKey, AuthorizationDecision> cache; // null when disabled

    public DecisionCache(Duration ttl, long maxSize) {
        if (ttl == null || ttl.isZero() || ttl.isNegative() || maxSize <= 0) {
            this.cache = null;
        } else {
            this.cache = Caffeine.newBuilder()
                    .expireAfterWrite(ttl)
                    .maximumSize(maxSize)
                    .build();
        }
    }

    /** Disabled cache (always recomputes) — used by tests asserting raw decision behaviour. */
    public static DecisionCache disabled() {
        return new DecisionCache(Duration.ZERO, 0);
    }

    /**
     * Returns a cached decision for {@code key} or computes + caches it. The
     * {@code loader} MUST be total (return allow/deny, never throw) so that a
     * failure is recorded as a fail-closed deny rather than propagating.
     */
    public AuthorizationDecision get(AuthzCacheKey key, Supplier<AuthorizationDecision> loader) {
        if (cache == null) {
            return loader.get();
        }
        return cache.get(key, k -> loader.get());
    }

    /** Cached decision or {@code null} on miss/disabled — used by the bounded-batch path. */
    public AuthorizationDecision getIfPresent(AuthzCacheKey key) {
        return cache == null ? null : cache.getIfPresent(key);
    }

    /** Store a decision (no-op when disabled). */
    public void put(AuthzCacheKey key, AuthorizationDecision decision) {
        if (cache != null) {
            cache.put(key, decision);
        }
    }

    public boolean isEnabled() {
        return cache != null;
    }
}

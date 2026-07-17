package com.example.commonauth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Revision-aware authorization cache (board #2556).
 *
 * <p><b>Why this class no longer caches on time alone.</b> Measured on k3d-test 2026-07-17: an admin
 * revoked a scope through the product API, the scope row went {@code REVOKED}, the outbox reached
 * {@code PROCESSED}, the OpenFGA tuple was deleted and {@code /authz/me} dropped the scope
 * immediately — yet the product endpoint kept answering {@code 200} for ~271 seconds, because the
 * previous implementation keyed a positive decision by subject alone and held it for a fixed TTL.
 * Revocation that takes effect "in about five minutes" is not revocation: the window covers exactly
 * the leaver / stolen-session case the control plane was built to close.
 *
 * <p><b>The invariant.</b> A cached decision is only reusable while the authorization revision it
 * was computed under is still current. The revision is cheap to read (a single counter) and is
 * bumped by the outbox after the FGA mutation and before the entry is marked processed, so
 * "revision changed" strictly implies "the previous decision may be stale". Both directions matter:
 * a fresh grant must appear as promptly as a revoke disappears, so positive and negative decisions
 * are treated the same here.
 *
 * <p><b>Failure is not a licence to keep saying yes.</b> If the revision cannot be read we cannot
 * know whether a cached allow still holds, so it is refused ({@link RevisionUnavailableException},
 * which callers surface as 503). A cached decision that grants nothing is still served: denying is
 * always safe, and an outage must not lock out users who never had access to begin with.
 *
 * <p>TTL survives only as a memory bound — it evicts entries nobody asks about. It is deliberately
 * NOT a correctness mechanism; do not shorten it to "reduce the stale window", because the window is
 * closed by the revision check, not by the clock.
 *
 * <p>Entries are replaced atomically per key rather than keyed by {@code subject:revision}: a
 * revision-suffixed key would leak one entry per principal per revision and grow without bound.
 * Refresh runs inside {@link ConcurrentHashMap#compute}, which single-flights concurrent callers for
 * the same principal (a global revision bump would otherwise stampede every in-flight request at
 * once) while leaving different principals fully parallel.
 */
public class AuthorizationContextCache {

    /**
     * Thrown when the authorization revision cannot be read and a cached decision that grants
     * something would otherwise have been reused. Callers must translate this to 503 — never to a
     * silent allow, and never to a silent deny that hides an outage.
     */
    public static class RevisionUnavailableException extends RuntimeException {
        public RevisionUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

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

    /**
     * Returns the authorization context for {@code key}, reusing a cached one only while it is
     * still valid under the current revision.
     *
     * @param key              principal identity for the cache entry. Must distinguish principals
     *                         across issuers and tenants — see {@link #key(String, String, Object)};
     *                         a bare subject is not sufficient.
     * @param revisionSupplier reads the current authorization revision. May throw; that is treated
     *                         as "unknown", not as "unchanged".
     * @param loader           computes a fresh context when there is nothing reusable.
     */
    public AuthorizationContext get(String key,
                                    LongSupplier revisionSupplier,
                                    Supplier<AuthorizationContext> loader) {
        Objects.requireNonNull(key, "cache key required");
        Objects.requireNonNull(revisionSupplier, "revision supplier required");
        Objects.requireNonNull(loader, "loader required");

        long revision;
        try {
            revision = revisionSupplier.getAsLong();
        } catch (RuntimeException ex) {
            return onRevisionUnavailable(key, ex);
        }

        Cached result = cache.compute(key, (k, existing) -> {
            Instant now = clock.instant();
            if (existing != null && existing.revision() == revision && existing.expiresAt().isAfter(now)) {
                return existing;
            }
            AuthorizationContext fresh = loader.get();
            if (fresh == null) {
                // A null answer is not a decision. Caching it as "grants nothing" would look like a
                // deny and, worse, would then be served during an outage as if it had been derived.
                throw new RevisionUnavailableException(
                        "authorization loader returned no context; refusing to cache a non-answer", null);
            }
            return new Cached(fresh, revision, now.plus(ttl));
        });
        return result.ctx();
    }

    /**
     * Builds a cache key that separates principals across issuers and tenants. Two identity
     * providers can mint the same {@code sub}, and the same human can carry different authority in
     * different tenants; collapsing either into one entry would serve one principal's decision to
     * another.
     *
     * <p><b>The encoding is length-prefixed, not delimiter-joined.</b> Joining on a separator is
     * not injective: with a space, {@code issuer="a b", subject="c"} and {@code issuer="a",
     * subject="b c"} collapse to the same key, and any separator one picks can legitimately occur
     * inside an issuer URL or an opaque subject. Two principals sharing one entry is an
     * authorization bug, so the key is unambiguous by construction rather than by assuming which
     * characters cannot appear.
     *
     * <p>{@code tenant} is a {@code String}, not an {@code Object}: relying on {@code toString()}
     * would make the key depend on whatever rendering a caller's tenant type happens to have, and
     * two distinct tenants whose {@code toString()} agreed would silently share one entry. Callers
     * pass a canonical id.
     *
     * @param tenant may be null when the deployment is single-tenant — it still occupies a distinct
     *               position in the key so that adding tenancy later cannot silently alias entries.
     */
    public static String key(String issuer, String subject, String tenant) {
        Objects.requireNonNull(subject, "subject required for cache key");
        StringBuilder sb = new StringBuilder(64);
        appendLengthPrefixed(sb, issuer);
        appendLengthPrefixed(sb, subject);
        appendLengthPrefixed(sb, tenant);
        return sb.toString();
    }

    /** {@code null} is encoded distinctly from the empty string \u2014 they are different principals. */
    private static void appendLengthPrefixed(StringBuilder sb, String part) {
        if (part == null) {
            sb.append("-:");
            return;
        }
        sb.append(part.length()).append(':').append(part);
    }

    private AuthorizationContext onRevisionUnavailable(String key, RuntimeException cause) {
        Cached existing = cache.get(key);
        if (existing != null && existing.ctx().grantsNothing()) {
            // Serving a cached deny during an outage is safe and keeps behaviour stable; it cannot
            // widen anyone's access.
            return existing.ctx();
        }
        throw new RevisionUnavailableException(
                "authorization revision unavailable; refusing to reuse a cached grant", cause);
    }

    /** Drops an entry — e.g. on an explicit invalidation signal. Correctness never depends on it. */
    public void evict(String key) {
        if (key != null) {
            cache.remove(key);
        }
    }

    public void clear() {
        cache.clear();
    }

    /** Entry count — for metrics/tests. */
    public int size() {
        return cache.size();
    }

    private record Cached(AuthorizationContext ctx, long revision, Instant expiresAt) {
    }
}

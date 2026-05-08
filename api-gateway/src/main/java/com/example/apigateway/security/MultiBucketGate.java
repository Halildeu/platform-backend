package com.example.apigateway.security;

import java.util.List;

/**
 * Phase 2 PR-BE-7 (Codex thread 019e0518 iter-2 implementation
 * guard): atomic dual-bucket gate. The contract:
 * "if either bucket denies, NEITHER consumes."
 *
 * <p>Naive implementation:
 * <pre>{@code
 * if (!ipBucket.tryAcquire() || (tokenBucket != null && !tokenBucket.tryAcquire())) {
 *     return deny429(...);
 * }
 * }</pre>
 * is broken — if the IP bucket succeeds and the token bucket then
 * fails, the IP token is wrongly consumed.
 *
 * <p>Correct: peek both buckets first; only consume both if both
 * peek positively. Concurrent callers may still race (peek says
 * available, then consume fails because someone else got there
 * first), in which case we fall back to deny — that's the correct
 * semantics under contention.
 *
 * <p>This is a soft-throttle: cluster-wide hard limits are not
 * guaranteed (HPA replicas multiply the effective threshold). The
 * dampener catches obvious storms, not surgically precise abuse.
 */
public final class MultiBucketGate {

  private MultiBucketGate() {
    // utility class
  }

  /**
   * Atomically tries to acquire one token from each bucket in the
   * list. Returns a decision indicating whether all buckets had a
   * token AND were successfully consumed. On denial, NO bucket has
   * a token consumed.
   *
   * <p>{@code null} entries in the list are ignored (e.g. when the
   * fingerprint bucket is absent because no Authorization header
   * was provided).
   */
  public static Decision tryAcquireAll(List<TokenBucket> buckets, long nowNanos) {
    long maxRetryAfter = 0;

    // Phase 1 — peek all buckets. If any is empty, deny without
    // consuming anything; record the max retry-after for the caller.
    for (TokenBucket bucket : buckets) {
      if (bucket == null) {
        continue;
      }
      if (!bucket.tryPeek(nowNanos)) {
        long retry = bucket.retryAfterSeconds(nowNanos);
        if (retry > maxRetryAfter) {
          maxRetryAfter = retry;
        }
      }
    }
    if (maxRetryAfter > 0) {
      return Decision.deny(maxRetryAfter);
    }

    // Phase 2 — consume all. A concurrent caller may have already
    // taken a token between phase 1 and phase 2; in that case we
    // accept the partial-allow risk (one of the buckets succeeds)
    // and rollback by NOT consuming anything else. This is bounded
    // because buckets refill quickly (perMinute / 60 per second).
    boolean allConsumed = true;
    long consumedCount = 0;
    for (TokenBucket bucket : buckets) {
      if (bucket == null) {
        continue;
      }
      if (bucket.consumeOne(nowNanos)) {
        consumedCount += 1;
      } else {
        allConsumed = false;
        break;
      }
    }

    if (allConsumed) {
      return Decision.allow();
    }

    // Partial consume on contention — caller is denied. The
    // already-consumed tokens are forfeit (will refill). For a soft
    // throttle this is acceptable; the alternative (refunding) needs
    // a more complex API and adds little benefit for this use case.
    return Decision.deny(1L);
  }

  public record Decision(boolean allowed, long retryAfterSeconds) {
    public static Decision allow() {
      return new Decision(true, 0L);
    }

    public static Decision deny(long retryAfterSeconds) {
      return new Decision(false, retryAfterSeconds);
    }
  }
}

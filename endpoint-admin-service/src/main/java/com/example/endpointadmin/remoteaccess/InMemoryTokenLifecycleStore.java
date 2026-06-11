package com.example.endpointadmin.remoteaccess;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference {@link TokenLifecycleStore} — DEV/TEST ONLY. Correct (atomic per-key via
 * {@link ConcurrentHashMap#compute}) but single-process; it does NOT survive across broker replicas.
 * Production MUST use a distributed atomic backing (Redis Lua / DB CAS) so revocation + single-use hold
 * cluster-wide (criterion #1). {@link #setAvailable(boolean)} simulates a store partition (criterion #7).
 */
public final class InMemoryTokenLifecycleStore implements TokenLifecycleStore {

    /** Per-jti record: lifecycle state + the token's recorded expiry (for deterministic time-liveness). */
    private record Entry(JtiState state, Instant expiresAt) {
    }

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();
    private volatile boolean available = true;

    /** Test hook: simulate the distributed store being unreachable (partition) → fail-closed. */
    public void setAvailable(boolean available) {
        this.available = available;
    }

    @Override
    public ConsumeOutcome consume(String jti, Instant expiresAt, Instant now) {
        if (jti == null || jti.isBlank() || expiresAt == null || now == null) {
            return ConsumeOutcome.INVALID;
        }
        if (!available) {
            return ConsumeOutcome.STORE_UNAVAILABLE; // fail-closed
        }
        ConsumeOutcome[] outcome = new ConsumeOutcome[1];
        entries.compute(jti, (k, current) -> {
            // Re-check availability INSIDE the atomic step so a partition mid-call cannot leak an ACCEPTED
            // (Codex 019eb54b absorb — no TOCTOU between availability and the state transition).
            if (!available) {
                outcome[0] = ConsumeOutcome.STORE_UNAVAILABLE;
                return current;
            }
            if (current != null) {
                outcome[0] = switch (current.state()) {
                    case USED -> ConsumeOutcome.ALREADY_USED;
                    case REVOKED -> ConsumeOutcome.REVOKED;
                    case EXPIRED -> ConsumeOutcome.EXPIRED;
                    case INVALID -> ConsumeOutcome.INVALID;
                    case UNSEEN -> ConsumeOutcome.ACCEPTED; // defensive (UNSEEN is never stored)
                };
                return current; // no change on a non-winning consume
            }
            // first sight of this jti — reject an already-expired token, else accept once.
            if (!now.isBefore(expiresAt)) {
                outcome[0] = ConsumeOutcome.EXPIRED;
                return new Entry(JtiState.EXPIRED, expiresAt);
            }
            outcome[0] = ConsumeOutcome.ACCEPTED;
            return new Entry(JtiState.USED, expiresAt);
        });
        return outcome[0];
    }

    @Override
    public MutationOutcome revoke(String jti) {
        if (jti == null || jti.isBlank()) {
            return MutationOutcome.NOOP;
        }
        if (!available) {
            return MutationOutcome.STORE_UNAVAILABLE; // a failed revoke surfaces (no silent NOOP)
        }
        MutationOutcome[] result = new MutationOutcome[]{MutationOutcome.NOOP};
        // Revocation always wins (even over EXPIRED) — forensic clarity + authoritative kill.
        entries.compute(jti, (k, current) -> {
            if (current != null && current.state() == JtiState.REVOKED) {
                return current; // already revoked → NOOP
            }
            result[0] = MutationOutcome.UPDATED;
            return new Entry(JtiState.REVOKED, current == null ? null : current.expiresAt());
        });
        return result[0];
    }

    @Override
    public MutationOutcome expire(String jti) {
        if (jti == null || jti.isBlank()) {
            return MutationOutcome.NOOP;
        }
        if (!available) {
            return MutationOutcome.STORE_UNAVAILABLE;
        }
        MutationOutcome[] result = new MutationOutcome[]{MutationOutcome.NOOP};
        entries.compute(jti, (k, current) -> {
            // expire does NOT override revoke; no-op if already revoked or already expired.
            if (current != null && (current.state() == JtiState.REVOKED || current.state() == JtiState.EXPIRED)) {
                return current;
            }
            result[0] = MutationOutcome.UPDATED;
            return new Entry(JtiState.EXPIRED, current == null ? null : current.expiresAt());
        });
        return result[0];
    }

    @Override
    public TokenLiveCheckResult isTokenLive(String jti, Instant now) {
        if (jti == null || jti.isBlank() || now == null) {
            return TokenLiveCheckResult.INVALID;
        }
        if (!available) {
            return TokenLiveCheckResult.STORE_UNAVAILABLE; // fail-closed
        }
        Entry e = entries.get(jti);
        if (e == null) {
            return TokenLiveCheckResult.NOT_FOUND;
        }
        return switch (e.state()) {
            case REVOKED -> TokenLiveCheckResult.REVOKED;
            case EXPIRED -> TokenLiveCheckResult.EXPIRED;
            case INVALID, UNSEEN -> TokenLiveCheckResult.INVALID;
            // deterministic time-liveness — past-TTL reads EXPIRED even without a prior expire() call
            case USED -> (e.expiresAt() != null && now.isBefore(e.expiresAt()))
                    ? TokenLiveCheckResult.LIVE
                    : TokenLiveCheckResult.EXPIRED;
        };
    }
}

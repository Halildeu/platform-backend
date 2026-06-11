package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 B2 — lifecycle state of a session token's {@code jti} (ADR-0033 §5 + §9b, Codex 019eb54b
 * criterion #1). The {@link TokenLifecycleStore} is the single source of truth; transitions are atomic.
 *
 * <pre>
 *   UNSEEN ──consume()──▶ USED
 *   (any) ──revoke()──▶ REVOKED        (authoritative; drives hard-kill)
 *   (any) ──expire()──▶ EXPIRED        (TTL elapsed)
 *   malformed/blank jti ▶ INVALID
 * </pre>
 */
public enum JtiState {
    /** Never presented — the only state from which a connect may be accepted. */
    UNSEEN,
    /** Consumed once — any further connect with this jti is a replay (single-use). */
    USED,
    /** Revoked — hard-kill: an active session holding this jti must be terminated. */
    REVOKED,
    /** TTL elapsed. */
    EXPIRED,
    /** Structurally invalid jti. */
    INVALID
}

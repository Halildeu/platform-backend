package com.example.endpointadmin.tpmattest;

import java.time.Instant;
import java.util.Optional;

/**
 * Faz 22.3B (ADR-0039) gate-4 — atomic, single-use, scope-bound nonce + server
 * secret store (verifier V1, design §10.5 T-1 anti-replay).
 *
 * <p>At {@code /enroll/tpm/nonce} the verifier {@link #issue issues} a nonce
 * (quote freshness) + a {@code serverSecret} (the TPM2_MakeCredential secret, for
 * V10 activation), both bound to a {@code scope} = {@code token_id|tenant|device}
 * derived from the server-side bootstrap-token claim (NOT caller input). At
 * {@code /enroll/tpm/attest} the secret is {@link #consume consumed exactly once}.
 *
 * <p><b>Multi-replica (Codex 019ec723 review):</b> the L1 (issue) and L2 (consume)
 * legs may hit different replicas. {@link InMemoryTpmNonceStore} is correct only
 * single-replica (or with session affinity). A distributed CAS/TTL backend
 * (Redis/Dynamo) implementing this interface is required before scaling endpoint-
 * admin-service beyond one replica with this feature on — tracked as a gate-4d
 * wiring task. The interface keeps that swap a drop-in.
 */
public interface TpmNonceStore {

    /** What the verifier needs back at /attest. {@code serverSecret} is the V10 activation secret. */
    record Consumed(byte[] nonce, byte[] serverSecret) {}

    /** Issue: bind {@code nonce}+{@code serverSecret} to {@code nonceId} under {@code scope}, expiring at {@code expiresAt}. */
    void issue(String nonceId, String scope, byte[] nonce, byte[] serverSecret, Instant expiresAt);

    /**
     * Consume exactly once. Returns the bound nonce+secret iff the entry exists,
     * is unexpired, and its scope matches; otherwise empty. Expired entries are
     * evicted; a scope mismatch is NOT consumed (entry retained — a wrong-scope
     * guess must not burn a legitimately-pending nonce).
     */
    Optional<Consumed> consume(String nonceId, String scope);

    /** Best-effort sweep of expired entries (safe to call periodically). */
    void evictExpired();
}

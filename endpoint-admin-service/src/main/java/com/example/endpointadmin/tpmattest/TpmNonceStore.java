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

    /**
     * What the verifier needs back at /attest. {@code serverSecret} is the V10 activation secret.
     *
     * <p>Faz 22.6 #548 Phase 1.5 (Codex {@code 019eff93} P0-1): {@code ekPubSha256} + {@code ekCertSha256}
     * are the L1-bound, V2-validated EK identity digests (lowercase 64-hex). The TPM device identity and the
     * persisted binding at /attest derive from THESE — never from an L2-resubmitted {@code ekCert} — closing
     * the borrowed-EK class (a caller passing TPM-A through L1 then a different valid EK cert at L2).
     */
    record Consumed(byte[] nonce, byte[] serverSecret, byte[] akName, String ekPubSha256, String ekCertSha256) {}

    /**
     * Issue: bind {@code nonce}+{@code serverSecret}+{@code akName}+EK identity to {@code nonceId} under
     * {@code scope}, expiring at {@code expiresAt}. {@code akName} is the L1-validated AK TPM Name;
     * at /attest the verifier MUST check the quote/certify-signing AK's recomputed Name equals it,
     * binding the activation-proven AK to the signing AK (Codex {@code 019ec723} gate-4d MUST#1).
     * {@code ekPubSha256}/{@code ekCertSha256} are the V2-validated EK identity (Codex {@code 019eff93} P0-1).
     */
    void issue(String nonceId, String scope, byte[] nonce, byte[] serverSecret, byte[] akName,
               String ekPubSha256, String ekCertSha256, Instant expiresAt);

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

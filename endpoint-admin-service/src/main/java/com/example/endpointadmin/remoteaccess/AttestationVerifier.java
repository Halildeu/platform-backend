package com.example.endpointadmin.remoteaccess;

import java.time.Instant;

/**
 * Faz 22.6 B1.3 — verifies AGENT/operator provenance (Codex 019eb694): the agent binary was produced by a
 * trusted builder, matches the expected SLSA policy, and carries a valid signed predicate. This upgrades
 * the existing {@code agentAttestation} precondition (precedence #2, after policy) from a trusted boolean
 * to an evidence-backed verdict; the runtime maps {@code verify(...).isVerified()} to {@code agentAttestation}.
 *
 * <p><b>Fail-closed:</b> ONLY {@link AttestationDecision#VERIFIED} is trustworthy. Missing / malformed
 * evidence, an untrusted (or mid-session-revoked) builder, a policy-hash mismatch, or an invalid signature
 * all mean kill. Continuous: the verdict is recomputed each heartbeat so a builder revoked mid-session
 * kills the live session (Codex 019eb694 Q2 — no STALE window; the predicate is signed-once but the
 * builder/policy allow-state is live).
 *
 * <p>B1.3 does a DETERMINISTIC crypto-minimum check (signature format + expected-field match — Codex Q4);
 * the heavy real Sigstore/cosign chain + SLSA envelope verification is the B1.4 transport seam (same
 * in-memory-vs-real split as the {@link CertTrustEvaluator}).
 */
public interface AttestationVerifier {

    /** The provenance verdict. Only {@link #VERIFIED} satisfies the {@code agentAttestation} precondition. */
    enum AttestationDecision {
        /** Trusted builder, expected SLSA policy, and a valid signed predicate. */
        VERIFIED(true),
        /** No / incomplete evidence presented — fail-closed (not implicitly trusted). */
        MISSING(false),
        /** The builder is not the expected one, or was revoked (compromised-builder disclosure). */
        UNTRUSTED_BUILDER(false),
        /** The SLSA predicate hash does not match the expected policy. */
        POLICY_MISMATCH(false),
        /** The predicate signature is absent or does not verify over the provenance. */
        SIGNATURE_INVALID(false);

        private final boolean verified;

        AttestationDecision(boolean verified) {
            this.verified = verified;
        }

        /** Whether this verdict satisfies the {@code agentAttestation} precondition (VERIFIED only). */
        public boolean isVerified() {
            return verified;
        }
    }

    /**
     * Verify the presented provenance at {@code now}. MUST be fail-closed: null/incomplete evidence, an
     * untrusted/revoked builder, a policy mismatch, or a bad signature all return a non-VERIFIED verdict.
     */
    AttestationDecision verify(AttestationEvidence evidence, Instant now);
}

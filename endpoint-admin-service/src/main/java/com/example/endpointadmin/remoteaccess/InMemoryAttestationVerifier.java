package com.example.endpointadmin.remoteaccess;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory reference {@link AttestationVerifier} — DEV/TEST + the disabled-by-default runtime default.
 * It models the policy decision deterministically (Codex 019eb694 Q4: a crypto-MINIMUM verification, not
 * just an allowlist match): the evidence must be complete, the builder must be the expected one and not
 * revoked, the SLSA predicate hash must match the expected policy, AND the predicate signature must equal
 * the deterministic expected signature over the canonical provenance tuple. The heavy real Sigstore/cosign
 * chain + SLSA envelope verification is the B1.4 seam.
 *
 * <p><b>Fail-closed everywhere</b> + <b>continuous</b>: {@link #revokeBuilder(String)} lets a mid-session
 * builder revocation flip a previously-VERIFIED session to UNTRUSTED_BUILDER on its next heartbeat.
 */
public final class InMemoryAttestationVerifier implements AttestationVerifier {

    private final String expectedBuilderId;
    private final String expectedPolicyHash;
    private final Set<String> revokedBuilders = ConcurrentHashMap.newKeySet();

    public InMemoryAttestationVerifier(String expectedBuilderId, String expectedPolicyHash) {
        if (expectedBuilderId == null || expectedBuilderId.isBlank()
                || expectedPolicyHash == null || expectedPolicyHash.isBlank()) {
            throw new IllegalArgumentException("expectedBuilderId + expectedPolicyHash must be non-blank");
        }
        this.expectedBuilderId = expectedBuilderId;
        this.expectedPolicyHash = expectedPolicyHash;
    }

    /** Revoke a builder mid-session (compromised-builder disclosure) → its sessions fail on next heartbeat. */
    public void revokeBuilder(String builderId) {
        if (builderId != null && !builderId.isBlank()) {
            revokedBuilders.add(builderId);
        }
    }

    /**
     * The deterministic expected signature over the canonical provenance tuple — the in-memory stand-in for
     * a real signature (B1.4 replaces this with Sigstore/cosign verification). A presented signature must
     * equal this exactly, so a forged/garbled predicate fails the crypto-minimum check.
     */
    public static String expectedSignature(String binaryDigest, String builderId, String slsaPredicateHash) {
        String canonical = binaryDigest + "|" + builderId + "|" + slsaPredicateHash;
        return CertThumbprint.ofDer(canonical.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public AttestationDecision verify(AttestationEvidence evidence, Instant now) {
        if (evidence == null || !evidence.isComplete() || now == null) {
            return AttestationDecision.MISSING; // no/incomplete provenance → fail-closed
        }
        if (revokedBuilders.contains(evidence.builderId()) || !expectedBuilderId.equals(evidence.builderId())) {
            return AttestationDecision.UNTRUSTED_BUILDER; // wrong or revoked builder
        }
        if (!expectedPolicyHash.equals(evidence.slsaPredicateHash())) {
            return AttestationDecision.POLICY_MISMATCH; // not the expected SLSA policy
        }
        String expectedSig =
                expectedSignature(evidence.binaryDigest(), evidence.builderId(), evidence.slsaPredicateHash());
        if (!constantTimeEquals(expectedSig, evidence.predicateSignature())) {
            return AttestationDecision.SIGNATURE_INVALID; // signature does not verify over the provenance
        }
        return AttestationDecision.VERIFIED;
    }

    /** Constant-time string compare (the signature is a secret-ish proof; don't leak via timing). */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        byte[] ba = a.getBytes(StandardCharsets.US_ASCII);
        byte[] bb = b.getBytes(StandardCharsets.US_ASCII);
        return java.security.MessageDigest.isEqual(ba, bb);
    }
}

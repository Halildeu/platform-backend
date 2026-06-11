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
 * chain + SLSA envelope verification is the B1.4 seam. <b>This is a PLACEHOLDER trust basis</b> (Codex
 * 019eb694 Q1) — the deterministic signature stand-in is NOT a real cryptographic counter-proof; even once
 * B1.3b wires it to the live heartbeat it is NOT final assurance until the B1.4 real Sigstore/cosign verify
 * lands. It exists to prove the fail-closed decision flow deterministically without a PKI.
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
        // hex-normalized + constant-time (Codex 019eb694 Q3): both sides are 64-hex; CertThumbprint.matches
        // decodes then compares the bytes so case/format can't create a false negative, and it is timing-safe.
        // Kept deliberately so the real B1.4 signature verify inherits the same normalization.
        if (!CertThumbprint.matches(expectedSig, evidence.predicateSignature())) {
            return AttestationDecision.SIGNATURE_INVALID; // signature does not verify over the provenance
        }
        return AttestationDecision.VERIFIED;
    }
}

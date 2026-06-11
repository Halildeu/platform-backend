package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 B1.3 — the agent provenance evidence presented at connect + each heartbeat, the input to
 * {@link AttestationVerifier} (Codex 019eb694). It upgrades the {@code agentAttestation} precondition from
 * a trusted boolean to verifiable evidence: the agent binary's digest (SBOM/binary sha256), the builder
 * that produced it, the SLSA predicate hash, and a signature over the provenance.
 *
 * <p>Pure data — no verification here; trust/builder/policy/signature checks are the verifier's job.
 * {@code builderId} / {@code slsaPredicateHash} / {@code predicateSignature} are the deterministic
 * crypto-minimum inputs B1.3 checks; B1.4 enriches with the real Sigstore/cosign bundle + SLSA envelope.
 */
public record AttestationEvidence(
        String binaryDigest,
        String builderId,
        String slsaPredicateHash,
        String predicateSignature) {

    /** Whether any provenance was actually presented (a non-blank binary digest). */
    public boolean isPresent() {
        return binaryDigest != null && !binaryDigest.isBlank();
    }

    /** Whether every field required for a crypto-minimum verification is present. */
    public boolean isComplete() {
        return isPresent()
                && builderId != null && !builderId.isBlank()
                && slsaPredicateHash != null && !slsaPredicateHash.isBlank()
                && predicateSignature != null && !predicateSignature.isBlank();
    }
}

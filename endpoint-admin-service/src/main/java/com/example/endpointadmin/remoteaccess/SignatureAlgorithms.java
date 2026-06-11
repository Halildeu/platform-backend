package com.example.endpointadmin.remoteaccess;

import java.util.Set;

/**
 * Faz 22.6 B1.4c — the shared signature-algorithm allowlist for the real attestation verifiers
 * (B1.4c-1 {@link KeyBasedAttestationVerifier} + B1.4c-2 {@link DsseProvenanceVerifier}). A strict allowlist
 * so a weak/unexpected configured algorithm is rejected fail-fast at construction (no algorithm-substitution
 * surface — the verifier dictates the algorithm; presented evidence never selects it). Codex 019eb7d6 #2.
 */
public final class SignatureAlgorithms {

    /** The accepted JCA {@link java.security.Signature} algorithm names. */
    public static final Set<String> ALLOWED = Set.of(
            "SHA256withECDSA", "SHA384withECDSA", "SHA512withECDSA", "Ed25519", "Ed448",
            "SHA256withRSA", "SHA384withRSA", "SHA512withRSA");

    /** @throws IllegalArgumentException if {@code algorithm} is null/blank or not on the allowlist. */
    public static String require(String algorithm) {
        if (algorithm == null || algorithm.isBlank()) {
            throw new IllegalArgumentException("signatureAlgorithm must be non-null/non-blank");
        }
        if (!ALLOWED.contains(algorithm)) {
            throw new IllegalArgumentException("signatureAlgorithm '" + algorithm + "' is not in the allowlist "
                    + ALLOWED + " — refusing a weak/unexpected algorithm");
        }
        return algorithm;
    }

    private SignatureAlgorithms() {
    }
}

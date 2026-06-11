package com.example.endpointadmin.remoteaccess;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Faz 22.6 B1.4c-1 — the REAL key-based {@link AttestationVerifier}: replaces the
 * {@link InMemoryAttestationVerifier} placeholder's SHA-256 stand-in with an ACTUAL cryptographic signature
 * verification of the agent's provenance against a configured signing public key. JDK-only (no Sigstore
 * client). Offline + deterministic.
 *
 * <p>The presented {@code predicateSignature} is a Base64-encoded cryptographic signature over the canonical
 * provenance tuple {@code "<binaryDigest>|<builderId>|<slsaPredicateHash>"} (UTF-8), verified with the
 * configured {@link PublicKey} + {@link Signature} algorithm (e.g. {@code SHA256withECDSA} / {@code Ed25519}).
 * This is a genuine counter-proof: only a holder of the matching private key can produce a verifying
 * signature, so a forged predicate, a wrong key, or a tampered field fails.
 *
 * <p><b>Fail-closed everywhere</b> (Codex 019eb694 doctrine): null/incomplete evidence → MISSING; an
 * untrusted or mid-session-revoked builder → UNTRUSTED_BUILDER; a policy-hash mismatch → POLICY_MISMATCH; a
 * signature that does not verify — for ANY reason (bad Base64, wrong key, wrong algorithm, tampered tuple,
 * or any thrown exception) → SIGNATURE_INVALID. <b>Continuous:</b> {@link #revokeBuilder(String)} flips a
 * previously-VERIFIED session on its next heartbeat.
 *
 * <p>The refined verdict ({@code UNTRUSTED_BUILDER} vs {@code SIGNATURE_INVALID} vs …) is consumed
 * INTERNALLY by the heartbeat for the audit record's kill reason — it is NOT signalled back to the agent, so
 * it is not an attacker-facing oracle (Codex 019eb7d6 #3).
 *
 * <p><b>Scope (B1.4c-1):</b> this verifies a signature over the provenance tuple with a SINGLE STATIC
 * configured key. A trusted-key RING + key-id selection (operational key rotation) is the B1.4c-3 factory's
 * concern (Codex 019eb7d6 #4). Parsing a real in-toto/DSSE SLSA envelope (extracting the fields + the
 * embedded signature from the envelope JSON) is B1.4c-2; the keyless Sigstore chain (Fulcio cert + Rekor
 * transparency log) — which needs network — is a further seam (like live OCSP).
 */
public final class KeyBasedAttestationVerifier implements AttestationVerifier {

    private final String expectedBuilderId;
    private final String expectedPolicyHash;
    private final PublicKey signingKey;
    private final String signatureAlgorithm;
    private final Set<String> revokedBuilders = ConcurrentHashMap.newKeySet();

    /**
     * @param signingKey         the trusted provenance-signing public key (the build system's key)
     * @param signatureAlgorithm a JCA {@link Signature} algorithm matching the key (e.g. {@code SHA256withECDSA})
     */
    public KeyBasedAttestationVerifier(String expectedBuilderId, String expectedPolicyHash,
                                       PublicKey signingKey, String signatureAlgorithm) {
        if (expectedBuilderId == null || expectedBuilderId.isBlank()
                || expectedPolicyHash == null || expectedPolicyHash.isBlank()) {
            throw new IllegalArgumentException("expectedBuilderId + expectedPolicyHash must be non-blank");
        }
        if (signingKey == null) {
            throw new IllegalArgumentException("signingKey must be non-null");
        }
        this.expectedBuilderId = expectedBuilderId;
        this.expectedPolicyHash = expectedPolicyHash;
        this.signingKey = signingKey;
        this.signatureAlgorithm = SignatureAlgorithms.require(signatureAlgorithm); // shared allowlist (B1.4c-2)
    }

    /** Revoke a builder mid-session (compromised-builder disclosure) → its sessions fail on next heartbeat. */
    public void revokeBuilder(String builderId) {
        if (builderId != null && !builderId.isBlank()) {
            revokedBuilders.add(builderId);
        }
    }

    /**
     * The canonical provenance bytes the build system signs. Each field is LENGTH-PREFIXED (4-byte big-endian
     * length + UTF-8 bytes) rather than delimiter-joined, so no field value can be confused with the framing —
     * two distinct field-triples can never collide to the same byte string (Codex 019eb7d6 #1: a naive
     * {@code a|b|c} join lets a {@code |} inside a field alias another triple). The signer MUST produce the
     * signature over exactly these bytes.
     */
    public static byte[] canonicalProvenance(String binaryDigest, String builderId, String slsaPredicateHash) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeLengthPrefixed(out, binaryDigest);
        writeLengthPrefixed(out, builderId);
        writeLengthPrefixed(out, slsaPredicateHash);
        return out.toByteArray();
    }

    private static void writeLengthPrefixed(ByteArrayOutputStream out, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        out.write((bytes.length >>> 24) & 0xFF);
        out.write((bytes.length >>> 16) & 0xFF);
        out.write((bytes.length >>> 8) & 0xFF);
        out.write(bytes.length & 0xFF);
        out.writeBytes(bytes);
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
        if (!signatureVerifies(evidence)) {
            return AttestationDecision.SIGNATURE_INVALID; // the predicate signature does not verify
        }
        return AttestationDecision.VERIFIED;
    }

    /**
     * Real cryptographic verification of the presented Base64 signature over the canonical tuple. Fail-closed:
     * a malformed Base64, a wrong key/algorithm, a tampered tuple, or ANY thrown exception → {@code false}.
     */
    private boolean signatureVerifies(AttestationEvidence evidence) {
        try {
            byte[] signatureBytes = Base64.getDecoder().decode(evidence.predicateSignature());
            byte[] canonical = canonicalProvenance(
                    evidence.binaryDigest(), evidence.builderId(), evidence.slsaPredicateHash());
            Signature verifier = Signature.getInstance(signatureAlgorithm);
            verifier.initVerify(signingKey);
            verifier.update(canonical);
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException | RuntimeException e) {
            // includes IllegalArgumentException from a malformed Base64; any failure → fail-closed
            return false; // an unverifiable signature can't prove provenance
        }
    }
}

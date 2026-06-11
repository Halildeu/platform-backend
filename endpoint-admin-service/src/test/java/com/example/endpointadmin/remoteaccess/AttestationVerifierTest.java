package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.AttestationVerifier.AttestationDecision;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 B1.3 — {@link InMemoryAttestationVerifier} fail-closed, crypto-minimum provenance semantics. */
class AttestationVerifierTest {

    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");
    private static final String BUILDER = "trusted-builder@slsa";
    private static final String POLICY = "expected-slsa-policy-hash";
    private static final String DIGEST = "agent-binary-sha256";

    private final InMemoryAttestationVerifier verifier = new InMemoryAttestationVerifier(BUILDER, POLICY);

    private static AttestationEvidence valid() {
        String sig = InMemoryAttestationVerifier.expectedSignature(DIGEST, BUILDER, POLICY);
        return new AttestationEvidence(DIGEST, BUILDER, POLICY, sig);
    }

    @Test
    void verifiedForCompleteTrustedSignedEvidence() {
        assertEquals(AttestationDecision.VERIFIED, verifier.verify(valid(), NOW));
        assertTrue(verifier.verify(valid(), NOW).isVerified());
    }

    @Test
    void missingForNullOrIncompleteEvidence() {
        assertEquals(AttestationDecision.MISSING, verifier.verify(null, NOW));
        assertEquals(AttestationDecision.MISSING, verifier.verify(new AttestationEvidence("", BUILDER, POLICY, "s"), NOW));
        assertEquals(AttestationDecision.MISSING, verifier.verify(new AttestationEvidence(DIGEST, " ", POLICY, "s"), NOW));
        assertEquals(AttestationDecision.MISSING, verifier.verify(new AttestationEvidence(DIGEST, BUILDER, POLICY, ""), NOW));
        assertEquals(AttestationDecision.MISSING, verifier.verify(valid(), null));
    }

    @Test
    void untrustedForWrongBuilder() {
        String sig = InMemoryAttestationVerifier.expectedSignature(DIGEST, "evil-builder", POLICY);
        assertEquals(AttestationDecision.UNTRUSTED_BUILDER,
                verifier.verify(new AttestationEvidence(DIGEST, "evil-builder", POLICY, sig), NOW));
    }

    @Test
    void untrustedForRevokedBuilderContinuous() {
        // a previously-trusted builder revoked mid-session (compromised-builder disclosure) → fails next check
        assertEquals(AttestationDecision.VERIFIED, verifier.verify(valid(), NOW));
        verifier.revokeBuilder(BUILDER);
        assertEquals(AttestationDecision.UNTRUSTED_BUILDER, verifier.verify(valid(), NOW));
    }

    @Test
    void policyMismatchForWrongPredicateHash() {
        String sig = InMemoryAttestationVerifier.expectedSignature(DIGEST, BUILDER, "other-policy");
        assertEquals(AttestationDecision.POLICY_MISMATCH,
                verifier.verify(new AttestationEvidence(DIGEST, BUILDER, "other-policy", sig), NOW));
    }

    @Test
    void signatureInvalidForForgedSignature() {
        // complete + trusted builder + right policy, but the signature does not verify over the provenance
        assertEquals(AttestationDecision.SIGNATURE_INVALID,
                verifier.verify(new AttestationEvidence(DIGEST, BUILDER, POLICY, "forged-signature"), NOW));
    }

    @Test
    void onlyVerifiedIsVerified() {
        for (AttestationDecision d : AttestationDecision.values()) {
            assertEquals(d == AttestationDecision.VERIFIED, d.isVerified(), d.name());
        }
    }
}

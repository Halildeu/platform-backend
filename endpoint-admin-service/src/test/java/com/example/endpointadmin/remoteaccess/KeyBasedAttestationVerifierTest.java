package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.AttestationVerifier.AttestationDecision;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 B1.4c-1 — {@link KeyBasedAttestationVerifier} REAL ECDSA signature verification of the agent
 * provenance. An EC P-256 keypair is generated in-test; the provenance tuple is signed with the private key
 * and verified against the public key, so the test is offline + deterministic in outcome (no committed key).
 */
class KeyBasedAttestationVerifierTest {

    private static final String BUILDER = "trusted-builder@slsa";
    private static final String POLICY = "expected-slsa-policy-hash";
    private static final String DIGEST = "agent-binary-sha256";
    private static final String ALG = "SHA256withECDSA";
    private static final Instant NOW = Instant.parse("2026-06-11T12:00:00Z");

    private final KeyPair keyPair = ecKeyPair();
    private final KeyBasedAttestationVerifier verifier =
            new KeyBasedAttestationVerifier(BUILDER, POLICY, keyPair.getPublic(), ALG);

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String sign(byte[] data, PrivateKey key) throws GeneralSecurityException {
        Signature s = Signature.getInstance(ALG);
        s.initSign(key);
        s.update(data);
        return Base64.getEncoder().encodeToString(s.sign());
    }

    private AttestationEvidence signed(String digest, String builder, String policy, PrivateKey key)
            throws GeneralSecurityException {
        String sig = sign(KeyBasedAttestationVerifier.canonicalProvenance(digest, builder, policy), key);
        return new AttestationEvidence(digest, builder, policy, sig);
    }

    @Test
    void verifiedForAValidlySignedProvenance() throws GeneralSecurityException {
        var d = verifier.verify(signed(DIGEST, BUILDER, POLICY, keyPair.getPrivate()), NOW);
        assertEquals(AttestationDecision.VERIFIED, d);
        assertTrue(d.isVerified());
    }

    @Test
    void signatureInvalidForAWrongSigningKey() throws GeneralSecurityException {
        // signed with a DIFFERENT key than the verifier trusts → does not verify
        var d = verifier.verify(signed(DIGEST, BUILDER, POLICY, ecKeyPair().getPrivate()), NOW);
        assertEquals(AttestationDecision.SIGNATURE_INVALID, d);
    }

    @Test
    void signatureInvalidForATamperedTuple() throws GeneralSecurityException {
        // the signature covers the ORIGINAL digest; the presented evidence swaps the digest → mismatch
        String sig = sign(KeyBasedAttestationVerifier.canonicalProvenance(DIGEST, BUILDER, POLICY),
                keyPair.getPrivate());
        var tampered = new AttestationEvidence("a-different-binary-digest", BUILDER, POLICY, sig);
        assertEquals(AttestationDecision.SIGNATURE_INVALID, verifier.verify(tampered, NOW));
    }

    @Test
    void signatureInvalidForGarbageSignature() {
        var d = verifier.verify(new AttestationEvidence(DIGEST, BUILDER, POLICY, "!!!not-base64!!!"), NOW);
        assertEquals(AttestationDecision.SIGNATURE_INVALID, d);
    }

    @Test
    void untrustedForWrongBuilderBeforeSignature() throws GeneralSecurityException {
        // builder is checked before the signature; a correctly-signed-but-wrong-builder predicate is untrusted
        var d = verifier.verify(signed(DIGEST, "evil-builder", POLICY, keyPair.getPrivate()), NOW);
        assertEquals(AttestationDecision.UNTRUSTED_BUILDER, d);
    }

    @Test
    void untrustedForRevokedBuilderContinuous() throws GeneralSecurityException {
        var evidence = signed(DIGEST, BUILDER, POLICY, keyPair.getPrivate());
        assertEquals(AttestationDecision.VERIFIED, verifier.verify(evidence, NOW));
        verifier.revokeBuilder(BUILDER); // compromised-builder disclosure mid-session
        assertEquals(AttestationDecision.UNTRUSTED_BUILDER, verifier.verify(evidence, NOW));
    }

    @Test
    void policyMismatchForWrongPredicateHash() throws GeneralSecurityException {
        var d = verifier.verify(signed(DIGEST, BUILDER, "other-policy", keyPair.getPrivate()), NOW);
        assertEquals(AttestationDecision.POLICY_MISMATCH, d);
    }

    @Test
    void missingForNullOrIncompleteEvidence() throws GeneralSecurityException {
        assertEquals(AttestationDecision.MISSING, verifier.verify(null, NOW));
        assertEquals(AttestationDecision.MISSING,
                verifier.verify(new AttestationEvidence("", BUILDER, POLICY, "sig"), NOW));
        assertEquals(AttestationDecision.MISSING,
                verifier.verify(signed(DIGEST, BUILDER, POLICY, keyPair.getPrivate()), null));
    }

    @Test
    void onlyVerifiedIsVerified() {
        for (AttestationDecision d : AttestationDecision.values()) {
            assertEquals(d == AttestationDecision.VERIFIED, d.isVerified(), d.name());
        }
    }
}

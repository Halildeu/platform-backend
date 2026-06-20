package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.CanonicalCommand;
import com.example.endpointadmin.remoteaccess.bridge.contract.OperationPermit;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 T-1b — {@link RemoteBridgePermitSigner}/{@link RemoteBridgePermitVerifier}: broker-private/agent-public. */
class RemoteBridgePermitSignerTest {

    private static final String ALG = "SHA256withECDSA";

    private static KeyPair ec() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
        g.initialize(256);
        return g.generateKeyPair();
    }

    private static OperationPermit unsigned(String kid) {
        return new OperationPermit(ALG, kid, 1, "policy-1", "dec-1", "sess-1", "op-1", "dev-1", "operator@x",
                RemoteSessionCapability.CONSTRAINED_PTY, CanonicalCommand.of("hostname").hash(), 1000L, 1300L, 7L, null);
    }

    private static OperationPermit permit(RemoteSessionCapability cap, String commandHash) {
        return new OperationPermit(ALG, "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1", "dev-1", "operator@x",
                cap, commandHash, 1000L, 1300L, 7L, null);
    }

    @Test
    void aSignedPermitVerifiesUnderTheBrokerPublicKey() throws Exception {
        KeyPair kp = ec();
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(kp.getPrivate(), "kid-1", ALG);
        RemoteBridgePermitVerifier verifier = new RemoteBridgePermitVerifier(kp.getPublic(), "kid-1");

        Optional<OperationPermit> signed = signer.sign(unsigned("kid-1"));
        assertTrue(signed.isPresent());
        assertTrue(verifier.verify(signed.get(), 1100L)); // within [1000,1300)
    }

    @Test
    void theSignerRefusesAnIncompleteOrInconsistentPermit() throws Exception {
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(ec().getPrivate(), "kid-1", ALG);
        // a blank security field -> no permit
        assertTrue(signer.sign(new OperationPermit(ALG, "kid-1", 1, "policy-1", "dec-1", "  ", "op-1", "dev-1",
                "operator@x", RemoteSessionCapability.CONSTRAINED_PTY, "", 1000L, 1300L, 7L, null)).isEmpty()); // blank sessionId
        // a non-positive validity window -> no permit
        assertTrue(signer.sign(new OperationPermit(ALG, "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1", "dev-1",
                "operator@x", RemoteSessionCapability.CONSTRAINED_PTY, "", 1300L, 1300L, 7L, null)).isEmpty());
        // an alg/kid that does not match the signer -> no permit
        assertTrue(signer.sign(unsigned("WRONG-KID")).isEmpty());
        assertTrue(signer.sign(null).isEmpty());
    }

    @Test
    void aDifferentKeyDoesNotVerifyAndATamperedFieldIsRejected() throws Exception {
        KeyPair kp = ec();
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(kp.getPrivate(), "kid-1", ALG);
        OperationPermit signed = signer.sign(unsigned("kid-1")).orElseThrow();

        // a verifier with a DIFFERENT key rejects it (agent cannot forge / cross-broker)
        assertFalse(new RemoteBridgePermitVerifier(ec().getPublic(), "kid-1").verify(signed, 1100L));
        // tampering any signed field invalidates the signature (canonicalPayload changes)
        OperationPermit tampered = new OperationPermit(signed.alg(), signed.kid(), signed.permitVersion(),
                signed.policyVersion(), signed.decisionId(), signed.sessionId(), "op-ESCALATED", signed.deviceId(),
                signed.operatorSubject(), signed.capability(), signed.commandHash(), signed.issuedAtEpochMillis(),
                signed.expiresAtEpochMillis(), signed.seq(), signed.signatureB64());
        assertFalse(new RemoteBridgePermitVerifier(kp.getPublic(), "kid-1").verify(tampered, 1100L));
    }

    @Test
    void anExpiredOrWrongKidPermitIsRejected() throws Exception {
        KeyPair kp = ec();
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(kp.getPrivate(), "kid-1", ALG);
        OperationPermit signed = signer.sign(unsigned("kid-1")).orElseThrow();
        RemoteBridgePermitVerifier verifier = new RemoteBridgePermitVerifier(kp.getPublic(), "kid-1");

        assertFalse(verifier.verify(signed, 1300L)); // expiry exclusive -> expired
        assertFalse(verifier.verify(signed, 999L));  // before issuance
        // a verifier expecting a different kid rejects
        assertFalse(new RemoteBridgePermitVerifier(kp.getPublic(), "kid-OTHER").verify(signed, 1100L));
    }

    @Test
    void theSignerRefusesANonPilotCapability() throws Exception {
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(ec().getPrivate(), "kid-1", ALG);
        assertTrue(signer.sign(permit(RemoteSessionCapability.FULL_RDP, "")).isEmpty());
        assertTrue(signer.sign(permit(RemoteSessionCapability.ELEVATION, "")).isEmpty());
        assertTrue(signer.sign(permit(RemoteSessionCapability.PORT_FORWARD, "")).isEmpty());
    }

    @Test
    void theSignerEnforcesCapabilityCommandHashConsistency() throws Exception {
        KeyPair kp = ec();
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(kp.getPrivate(), "kid-1", ALG);
        RemoteBridgePermitVerifier verifier = new RemoteBridgePermitVerifier(kp.getPublic(), "kid-1");
        // CONSTRAINED_PTY MUST carry a command hash
        assertTrue(signer.sign(permit(RemoteSessionCapability.CONSTRAINED_PTY, "")).isEmpty());
        // VIEW_ONLY MUST NOT carry a command hash
        assertTrue(signer.sign(permit(RemoteSessionCapability.VIEW_ONLY,
                CanonicalCommand.of("hostname").hash())).isEmpty());
        // VIEW_ONLY with an empty hash is the valid screen-view permit -> signs + verifies
        Optional<OperationPermit> view = signer.sign(permit(RemoteSessionCapability.VIEW_ONLY, ""));
        assertTrue(view.isPresent());
        assertTrue(verifier.verify(view.get(), 1100L));
    }

    @Test
    void theSignerAndVerifierArePinnedToEcP256() throws Exception {
        KeyPair ec = ec();
        // a different (still-allowlisted elsewhere) algorithm is rejected at the permit boundary
        assertThrowsIae(() -> new RemoteBridgePermitSigner(ec.getPrivate(), "kid-1", "SHA384withECDSA"));
        // an RSA key is rejected (not P-256)
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        KeyPair rsaKp = rsa.generateKeyPair();
        assertThrowsIae(() -> new RemoteBridgePermitSigner(rsaKp.getPrivate(), "kid-1", ALG));
        assertThrowsIae(() -> new RemoteBridgePermitVerifier(rsaKp.getPublic(), "kid-1"));
    }

    @Test
    void theP256PinIsExactNotJustFieldSize() throws Exception {
        // a DIFFERENT field size (P-384) is rejected
        java.security.KeyPairGenerator g = java.security.KeyPairGenerator.getInstance("EC");
        g.initialize(new java.security.spec.ECGenParameterSpec("secp384r1"));
        KeyPair p384 = g.generateKeyPair();
        assertThrowsIae(() -> new RemoteBridgePermitSigner(p384.getPrivate(), "kid-1", ALG));
        // a SAME-field-size but DIFFERENT curve (secp256k1) is rejected — proves the exact-curve pin
        try {
            java.security.KeyPairGenerator k = java.security.KeyPairGenerator.getInstance("EC");
            k.initialize(new java.security.spec.ECGenParameterSpec("secp256k1"));
            KeyPair k256 = k.generateKeyPair();
            assertThrowsIae(() -> new RemoteBridgePermitSigner(k256.getPrivate(), "kid-1", ALG));
        } catch (java.security.InvalidAlgorithmParameterException unsupported) {
            // secp256k1 not available in this JDK build — the full-param compare still rejects it; P-384 covers non-r1
        }
    }

    @Test
    void theSignerRejectsAWrongVersionOrNonPositiveSeq() throws Exception {
        RemoteBridgePermitSigner signer = new RemoteBridgePermitSigner(ec().getPrivate(), "kid-1", ALG);
        OperationPermit wrongVersion = new OperationPermit(ALG, "kid-1", 2, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", RemoteSessionCapability.VIEW_ONLY, "", 1000L, 1300L, 7L, null);
        OperationPermit zeroSeq = new OperationPermit(ALG, "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", RemoteSessionCapability.VIEW_ONLY, "", 1000L, 1300L, 0L, null);
        OperationPermit negativeSeq = new OperationPermit(ALG, "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", RemoteSessionCapability.VIEW_ONLY, "", 1000L, 1300L, -1L, null);
        assertTrue(signer.sign(wrongVersion).isEmpty());
        assertTrue(signer.sign(zeroSeq).isEmpty());
        assertTrue(signer.sign(negativeSeq).isEmpty());
    }

    @Test
    void constructorsValidateTheirInputs() throws Exception {
        KeyPair kp = ec();
        assertThrowsIae(() -> new RemoteBridgePermitSigner(null, "kid", ALG));
        assertThrowsIae(() -> new RemoteBridgePermitSigner(kp.getPrivate(), "  ", ALG));
        assertThrowsIae(() -> new RemoteBridgePermitVerifier(null, "kid"));
        assertThrowsIae(() -> new RemoteBridgePermitVerifier(kp.getPublic(), " "));
    }

    private static void assertThrowsIae(org.junit.jupiter.api.function.Executable e) {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, e);
    }
}

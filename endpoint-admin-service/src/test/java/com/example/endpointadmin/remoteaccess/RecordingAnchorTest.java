package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.RecordingAnchorVerifier.AnchorVerdict;
import com.example.endpointadmin.remoteaccess.SessionRecordingChain.RecordKind;
import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 C-2 — {@link RecordingAnchorSigner} / {@link RecordingAnchorVerifier}: the out-of-band signed
 * completeness proof (truncation / replacement detection) over a {@link SessionRecordingChain}. In-test EC
 * P-256 keypair; offline + deterministic-in-outcome.
 */
class RecordingAnchorTest {

    private static final String ALG = "SHA256withECDSA";
    private static final String CHAIN_ID = "sess-42";

    private final KeyPair keyPair = ecKeyPair();
    private final RecordingAnchorSigner signer = new RecordingAnchorSigner(CHAIN_ID, keyPair.getPrivate(), ALG);
    private final RecordingAnchorVerifier verifier = new RecordingAnchorVerifier(keyPair.getPublic(), ALG);

    private static KeyPair ecKeyPair() {
        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            return kpg.generateKeyPair();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static SessionRecordingChain chainOf(int n) {
        SessionRecordingChain chain = new SessionRecordingChain();
        for (int i = 0; i < n; i++) {
            chain.append(RecordKind.AGENT_OUTPUT, "content-" + i, 1000L + i);
        }
        return chain;
    }

    @Test
    void aSignedAnchorVerifiesAndIsConsistentWithItsChain() {
        SessionRecordingChain chain = chainOf(3);
        RecordingAnchor anchor = signer.anchor(chain, 5000L);
        assertTrue(verifier.verifySignature(anchor));
        assertEquals(AnchorVerdict.CONSISTENT, verifier.audit(chain, anchor));
    }

    @Test
    void appendOnlyGrowthStaysConsistent() {
        SessionRecordingChain chain = chainOf(2);
        RecordingAnchor anchor = signer.anchor(chain, 5000L); // anchored at count 2
        chain.append(RecordKind.SESSION_END, "later", 9000L); // grows to 3 — anchored prefix intact
        assertEquals(AnchorVerdict.CONSISTENT, verifier.audit(chain, anchor));
    }

    @Test
    void anEmptyChainAnchorsAtGenesisAndStaysConsistentAsItGrows() {
        SessionRecordingChain chain = new SessionRecordingChain();
        RecordingAnchor anchor = signer.anchor(chain, 1L); // count 0, head = GENESIS
        assertEquals(SessionRecordingChain.GENESIS_HASH, anchor.headHash());
        chain.append(RecordKind.SESSION_START, "x", 2L);
        assertEquals(AnchorVerdict.CONSISTENT, verifier.audit(chain, anchor));
    }

    @Test
    void aTruncatedChainIsDetected() {
        // anchored at count 3, but the live chain is now shorter than the signed count
        RecordingAnchor anchor = signer.anchor(chainOf(3), 5000L);
        assertEquals(AnchorVerdict.TRUNCATED, verifier.audit(chainOf(2), anchor));
    }

    @Test
    void aReplacedChainIsDetected() {
        // anchored over chain A (3 entries); a DIFFERENT 3-entry chain has a different head at the anchored count
        RecordingAnchor anchorA = signer.anchor(chainOf(3), 5000L);
        SessionRecordingChain chainB = new SessionRecordingChain();
        chainB.append(RecordKind.AGENT_OUTPUT, "DIFFERENT-0", 1000L);
        chainB.append(RecordKind.AGENT_OUTPUT, "DIFFERENT-1", 1001L);
        chainB.append(RecordKind.AGENT_OUTPUT, "DIFFERENT-2", 1002L);
        assertEquals(AnchorVerdict.REPLACED, verifier.audit(chainB, anchorA));
    }

    @Test
    void aForgedSignatureIsUnsigned() {
        RecordingAnchor anchor = signer.anchor(chainOf(2), 5000L);
        RecordingAnchor forged = new RecordingAnchor(
                anchor.chainId(), anchor.count(), anchor.headHash(), anchor.timestampMillis(), "Zm9yZ2Vk");
        assertFalse(verifier.verifySignature(forged));
        assertEquals(AnchorVerdict.UNSIGNED, verifier.audit(chainOf(2), forged));
    }

    @Test
    void anAnchorSignedByAnotherKeyDoesNotVerify() {
        var otherSigner = new RecordingAnchorSigner(CHAIN_ID, ecKeyPair().getPrivate(), ALG);
        RecordingAnchor foreign = otherSigner.anchor(chainOf(2), 5000L);
        assertFalse(verifier.verifySignature(foreign));
        assertEquals(AnchorVerdict.UNSIGNED, verifier.audit(chainOf(2), foreign));
    }

    @Test
    void aTamperedAnchorFieldBreaksTheSignature() {
        RecordingAnchor anchor = signer.anchor(chainOf(3), 5000L);
        // same signature, but the count was bumped → the canonical no longer matches what was signed
        RecordingAnchor tampered = new RecordingAnchor(
                anchor.chainId(), anchor.count() + 1, anchor.headHash(), anchor.timestampMillis(), anchor.signature());
        assertFalse(verifier.verifySignature(tampered));
    }

    @Test
    void onlyConsistentIsSound() {
        for (AnchorVerdict v : AnchorVerdict.values()) {
            assertEquals(v == AnchorVerdict.CONSISTENT, v.isSound(), v.name());
        }
    }
}

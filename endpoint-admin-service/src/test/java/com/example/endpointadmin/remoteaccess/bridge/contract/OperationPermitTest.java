package com.example.endpointadmin.remoteaccess.bridge.contract;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 T-1a — {@link OperationPermit} canonical signed payload: stable, field-sensitive, signature-excluded. */
class OperationPermitTest {

    private static OperationPermit base() {
        return new OperationPermit("SHA256withECDSA", "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY,
                CanonicalCommand.of("hostname").hash(), 1000L, 1300L, 7L, null);
    }

    @Test
    void theCanonicalPayloadIsStableForTheSameSecurityFields() {
        assertArrayEquals(base().canonicalPayload(), base().canonicalPayload());
    }

    @Test
    void theSignatureIsNotPartOfTheSignedPayload() {
        // two permits differing ONLY in the signature must produce identical signed bytes
        assertArrayEquals(base().canonicalPayload(), base().withSignature("c2ln").canonicalPayload());
    }

    @Test
    void theCanonicalPayloadChangesWhenAnySecurityFieldChanges() {
        byte[] b = base().canonicalPayload();
        assertPayloadDiffers(b, new OperationPermit("SHA256withECDSA", "kid-1", 1, "policy-1", "dec-1", "sess-DIFF",
                "op-1", "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY,
                CanonicalCommand.of("hostname").hash(), 1000L, 1300L, 7L, null).canonicalPayload()); // sessionId
        assertPayloadDiffers(b, baseCommandHash(CanonicalCommand.of("whoami").hash())); // commandHash
        assertPayloadDiffers(b, base2(RemoteSessionCapability.VIEW_ONLY));               // capability
        assertPayloadDiffers(b, base3(9999L));                                          // expiresAt
        assertPayloadDiffers(b, base4(99L));                                            // seq
    }

    @Test
    void freshnessIsBoundedByTheIssuedAndExpiryWindow() {
        OperationPermit p = base(); // issued 1000, expires 1300
        assertTrue(p.isFresh(1000));
        assertTrue(p.isFresh(1299));
        assertFalse(p.isFresh(1300)); // expiry exclusive
        assertFalse(p.isFresh(999));  // before issuance
    }

    @Test
    void withSignatureSetsTheSignatureAndPreservesEverythingElse() {
        OperationPermit signed = base().withSignature("c2lnbmF0dXJl");
        assertTrue("c2lnbmF0dXJl".equals(signed.signatureB64()));
        assertArrayEquals(base().canonicalPayload(), signed.canonicalPayload());
    }

    // --- helpers: vary one field, assert the canonical payload differs ---
    private static void assertPayloadDiffers(byte[] baseBytes, byte[] variant) {
        assertFalse(Arrays.equals(baseBytes, variant), "canonical payload must change");
    }

    private static byte[] baseCommandHash(String commandHash) {
        return new OperationPermit("SHA256withECDSA", "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY, commandHash, 1000L, 1300L, 7L, null)
                .canonicalPayload();
    }

    private static byte[] base2(RemoteSessionCapability cap) {
        return new OperationPermit("SHA256withECDSA", "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", cap, CanonicalCommand.of("hostname").hash(), 1000L, 1300L, 7L, null)
                .canonicalPayload();
    }

    private static byte[] base3(long expiresAt) {
        return new OperationPermit("SHA256withECDSA", "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY,
                CanonicalCommand.of("hostname").hash(), 1000L, expiresAt, 7L, null).canonicalPayload();
    }

    private static byte[] base4(long seq) {
        return new OperationPermit("SHA256withECDSA", "kid-1", 1, "policy-1", "dec-1", "sess-1", "op-1",
                "dev-1", "operator@x", RemoteSessionCapability.CONSTRAINED_PTY,
                CanonicalCommand.of("hostname").hash(), 1000L, 1300L, seq, null).canonicalPayload();
    }
}

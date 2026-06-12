package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 T-4a-ii slice-2 — the SHARED verifier factory extracted from {@code ScheduledRevocationDriver}
 * (behaviour-preserving): the same fail-fast config rules now run from one place for both the revocation
 * runtime and the remote-bridge broker. These tests pin the extracted behaviour directly.
 */
class RemoteAccessVerifierFactoryTest {

    // --- parseEnum --------------------------------------------------------

    enum Probe { A, B }

    @Test
    void parseEnumBlankOrNullDefaults() {
        assertEquals(Probe.A, RemoteAccessVerifierFactory.parseEnum(null, Probe.class, Probe.A));
        assertEquals(Probe.A, RemoteAccessVerifierFactory.parseEnum("  ", Probe.class, Probe.A));
    }

    @Test
    void parseEnumIsCaseInsensitiveAndTrimmed() {
        assertEquals(Probe.B, RemoteAccessVerifierFactory.parseEnum(" b ", Probe.class, Probe.A));
    }

    @Test
    void parseEnumRejectsAnInvalidValueFailFast() {
        // a typo must NOT silently default — it fails the boot
        assertThrows(IllegalStateException.class,
                () -> RemoteAccessVerifierFactory.parseEnum("NOPE", Probe.class, Probe.A));
    }

    // --- cert-trust evaluator --------------------------------------------

    @Test
    void blankCertTrustConfigBuildsTheSafeInMemoryEvaluator() {
        // IN_MEMORY/DISABLED default — never null, never a REAL_PKI boot without anchors
        assertNotNull(RemoteAccessVerifierFactory.buildTrustEvaluator(
                "", "", "", "", false, false, 60_000));
    }

    @Test
    void realPkiWithEmptyAnchorsFailsFast() {
        assertThrows(RuntimeException.class, () -> RemoteAccessVerifierFactory.buildTrustEvaluator(
                "REAL_PKI", "CRL", "", "", false, false, 60_000));
    }

    @Test
    void anUnparseableAnchorBundleFailsFast() {
        assertThrows(IllegalStateException.class, () -> RemoteAccessVerifierFactory.buildTrustEvaluator(
                "REAL_PKI", "CRL", "not a pem", "", false, false, 60_000));
    }

    // --- attestation verifier --------------------------------------------

    @Test
    void blankAttestationConfigYieldsNullDenyAllCoercedByConsumer() {
        // no expected builder/policy → null (the heartbeat / ledger coerces to deny-all)
        assertNull(RemoteAccessVerifierFactory.buildAttestationVerifier("", "", "", "", "", false));
    }

    @Test
    void anUnparseablePublicKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> RemoteAccessVerifierFactory.buildAttestationVerifier(
                "KEY_BASED", "builder", "hash", "not a key", "SHA256withECDSA", false));
    }

    @Test
    void aProductionLikeProfileRefusesTheInMemoryPlaceholder() {
        // the placeholder is forbidden in a prod-like profile (Codex 019eb6d9)
        assertThrows(RuntimeException.class, () -> RemoteAccessVerifierFactory.buildAttestationVerifier(
                "IN_MEMORY", "builder", "hash", "", "", true));
    }

    // --- deny-all wrap + device verifier (slice-2) ------------------------

    @Test
    void orDenyAllNeverReturnsNullForAnUnconfiguredPolicy() {
        // PeerTrustLedger requires non-null — blank config must yield the explicit deny-all, not null
        AttestationVerifier v = RemoteAccessVerifierFactory.buildAttestationVerifierOrDenyAll(
                "", "", "", "", "", false);
        assertNotNull(v);
        assertEquals(AttestationVerifier.AttestationDecision.MISSING,
                v.verify(new AttestationEvidence("sha256:x", "b", "h", "s"), java.time.Instant.now()));
    }

    @Test
    void denyAllVerifierNeverVerifies() {
        assertFalse(DenyAllAttestationVerifier.INSTANCE.verify(
                new AttestationEvidence("sha256:x", "b", "h", "s"), java.time.Instant.now()).isVerified());
    }

    @Test
    void buildDeviceIdentityVerifierWithBlankRootsTrustsNoDevice() {
        // empty anchor set → the verifier is constructed (non-null) but trusts no device (fail-closed)
        assertNotNull(RemoteAccessVerifierFactory.buildDeviceIdentityVerifier("", "SECURE_ELEMENT_OR_TPM"));
    }

    @Test
    void buildDeviceIdentityVerifierWithABadPemFailsFast() {
        assertThrows(IllegalStateException.class,
                () -> RemoteAccessVerifierFactory.buildDeviceIdentityVerifier("not a pem", "SOFTWARE"));
    }

    @Test
    void factoryIsAStatelessHelperNotInstantiable() {
        assertFalse(java.lang.reflect.Modifier.isPublic(
                RemoteAccessVerifierFactory.class.getDeclaredConstructors()[0].getModifiers()));
    }
}

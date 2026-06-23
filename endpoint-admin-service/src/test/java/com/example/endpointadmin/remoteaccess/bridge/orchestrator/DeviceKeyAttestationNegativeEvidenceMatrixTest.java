package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.TrustAnchorLoader;
import com.example.endpointadmin.remoteaccess.X509ChainParser;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * #548 negative evidence harness: drives a v1 device-key attestation envelope through the production parser,
 * peer-trust ledger, and session device-trust verifier. This is source-level fail-closed coverage only; live TPM /
 * device-key provisioning and field evidence remain the #548 blocker.
 */
class DeviceKeyAttestationNegativeEvidenceMatrixTest {

    private static final Instant VERIFY_NOW = Instant.parse("2035-06-01T00:00:00Z");
    private static final long NOW = VERIFY_NOW.toEpochMilli();
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String DEVICE = "22222222-2222-2222-2222-222222222222";
    private static final String PEER_KEY = "peer-1";
    private static final String ALG = "SHA256withECDSA";

    private final byte[] rootDer;
    private final byte[] deviceKeyDer;
    private final byte[] trustedSignature;
    private final Set<TrustAnchor> trustedRoots;
    private final TransportBoundPeerEvidenceParser parser = new TransportBoundPeerEvidenceParser();

    DeviceKeyAttestationNegativeEvidenceMatrixTest() throws Exception {
        this.rootDer = certResource("device-attestation-root.pem").getEncoded();
        this.deviceKeyDer = resource("device-key.der");
        this.trustedSignature = resource("sig-se-tpm-true.bin");
        this.trustedRoots = TrustAnchorLoader.load(List.of(rootDer));
    }

    @Test
    void trustedEnvelopePromotesFreshPeerTrustToHardwareDeviceTrust() {
        PeerTrust trust = record(trustedVerifier(), validEnvelope(), peer(DEVICE), NOW);

        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session(TENANT, DEVICE), trust, NOW);

        assertTrue(trust.deviceTrusted());
        assertTrue(decision.trusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.HARDWARE_KEY_ATTESTATION, decision.basis());
        assertEquals("hardware-key-attestation-verified", decision.reason());
    }

    @Test
    void missingDeviceKeyEvidenceNeverPromotesHardwareDeviceTrust() {
        PeerTrust trust = record(trustedVerifier(), slsaOnlyEnvelope(), peer(DEVICE), NOW);

        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session(TENANT, DEVICE), trust, NOW);

        assertFalse(trust.deviceTrusted());
        assertFalse(decision.trusted());
        assertEquals("hardware-key-attestation-unverified", decision.reason());
    }

    @Test
    void malformedDeviceKeyEnvelopeNeverPromotesHardwareDeviceTrust() {
        PeerTrust trust = record(trustedVerifier(), b64("{\"v\":1,\"deviceKey\":"),
                peer(DEVICE), NOW);

        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session(TENANT, DEVICE), trust, NOW);

        assertFalse(trust.deviceTrusted());
        assertFalse(decision.trusted());
        assertEquals("hardware-key-attestation-unverified", decision.reason());
    }

    @Test
    void signatureInvalidDeviceKeyEvidenceNeverPromotesHardwareDeviceTrust() {
        byte[] corrupt = trustedSignature.clone();
        corrupt[corrupt.length - 1] ^= 0x01;

        PeerTrust trust = record(trustedVerifier(), envelope(corrupt, rootDer), peer(DEVICE), NOW);

        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session(TENANT, DEVICE), trust, NOW);

        assertFalse(trust.deviceTrusted());
        assertFalse(decision.trusted());
        assertEquals("hardware-key-attestation-unverified", decision.reason());
    }

    @Test
    void untrustedDeviceRootNeverPromotesHardwareDeviceTrust() {
        PeerTrust trust = record(untrustedRootVerifier(), validEnvelope(), peer(DEVICE), NOW);

        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session(TENANT, DEVICE), trust, NOW);

        assertFalse(trust.deviceTrusted());
        assertFalse(decision.trusted());
        assertEquals("hardware-key-attestation-unverified", decision.reason());
    }

    @Test
    void stalePeerTrustIsNoTrustAtSessionDecisionTime() {
        PeerTrustLedger ledger = ledger(trustedVerifier());
        ledger.record(peer(DEVICE), hello(validEnvelope()), NOW);

        Optional<PeerTrust> stale = ledger.fresh(PEER_KEY, NOW + 30_001L);
        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session(TENANT, DEVICE), stale.orElse(null), NOW + 30_001L);

        assertTrue(stale.isEmpty());
        assertFalse(decision.trusted());
        assertEquals("no-fresh-peer-trust", decision.reason());
    }

    @Test
    void certBoundDeviceIdentityMismatchDeniesEvenWithTrustedHardwareEvidence() {
        PeerTrust trust = record(trustedVerifier(), validEnvelope(), peer(DEVICE), NOW);

        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session(TENANT, "33333333-3333-3333-3333-333333333333"), trust, NOW);

        assertTrue(trust.deviceTrusted());
        assertFalse(decision.trusted());
        assertEquals("device-identity-mismatch", decision.reason());
    }

    @Test
    void missingTenantIdentityDeniesEvenWithTrustedHardwareEvidence() {
        PeerTrust trust = record(trustedVerifier(), validEnvelope(), peer(DEVICE), NOW);

        SessionDeviceTrustVerifier.DeviceTrustDecision decision =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session("", DEVICE), trust, NOW);

        assertTrue(trust.deviceTrusted());
        assertFalse(decision.trusted());
        assertEquals("missing-session-identity", decision.reason());
    }

    private PeerTrust record(DeviceIdentityVerifier deviceVerifier, String evidenceB64,
                             PeerIdentity peer, long nowEpochMillis) {
        return ledger(deviceVerifier).record(peer, hello(evidenceB64), nowEpochMillis);
    }

    private PeerTrustLedger ledger(DeviceIdentityVerifier deviceVerifier) {
        return new PeerTrustLedger((cert, now) -> CertTrustEvaluator.TrustDecision.NOT_TRUSTED,
                (evidence, now) -> AttestationVerifier.AttestationDecision.MISSING,
                deviceVerifier, parser, 30_000L);
    }

    private DeviceIdentityVerifier trustedVerifier() {
        return new DeviceIdentityVerifier(trustedRoots,
                DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM);
    }

    private DeviceIdentityVerifier untrustedRootVerifier() {
        return new DeviceIdentityVerifier(Set.of(),
                DeviceIdentityVerifier.DeviceProtectionLevel.SECURE_ELEMENT_OR_TPM);
    }

    private static PeerIdentity peer(String certBoundDeviceId) {
        return new PeerIdentity(PEER_KEY, Optional.of(certBoundDeviceId), List.of());
    }

    private static RemoteBridgeSession session(String tenant, String deviceId) {
        return new RemoteBridgeSession("s1", PEER_KEY, deviceId, "operator@x", tenant, "Operator X",
                Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 60_000L, NOW, State.ACTIVE);
    }

    private static RemoteBridgeMessages.AgentHello hello(String evidenceB64) {
        return new RemoteBridgeMessages.AgentHello("0.2.27", DEVICE, "ignored-self-claimed-cert",
                evidenceB64, "rb-v1", Set.of());
    }

    private String validEnvelope() {
        return envelope(trustedSignature, rootDer);
    }

    private String envelope(byte[] signature, byte[] chainDer) {
        return b64("""
                {
                  "v": 1,
                  "deviceKey": {
                    "keyDer": "%s",
                    "protectionLevel": "SECURE_ELEMENT_OR_TPM",
                    "nonExportable": true,
                    "signature": "%s",
                    "algorithm": "%s",
                    "chainDer": ["%s"]
                  }
                }
                """.formatted(b64Bytes(deviceKeyDer), b64Bytes(signature), ALG, b64Bytes(chainDer)));
    }

    private static String slsaOnlyEnvelope() {
        return b64("""
                {
                  "v": 1,
                  "slsa": {
                    "binaryDigest": "sha256:abc",
                    "builderId": "builder",
                    "slsaPredicateHash": "hash",
                    "predicateSignature": "sig"
                  }
                }
                """);
    }

    private static byte[] resource(String name) throws Exception {
        try (InputStream in = DeviceKeyAttestationNegativeEvidenceMatrixTest.class
                .getResourceAsStream("/remoteaccess/device-attestation/" + name)) {
            return in.readAllBytes();
        }
    }

    private static X509Certificate certResource(String name) throws Exception {
        return X509ChainParser.parseCertificate(resource(name));
    }

    private static String b64(String value) {
        return b64Bytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64Bytes(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}

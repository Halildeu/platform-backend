package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PeerDeviceKeyAttestationSessionDeviceTrustVerifierTest {

    private static final long NOW = 1_000_000L;
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static RemoteBridgeSession session(String peerKey, String tenant, String deviceId) {
        return new RemoteBridgeSession("s1", peerKey, deviceId, "operator@x", tenant, "Operator X",
                Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 60_000L, NOW, State.ACTIVE);
    }

    private static PeerTrust trust(boolean deviceTrusted) {
        return new PeerTrust(true, true, deviceTrusted, Optional.of("dev-1"), "dev-1", NOW);
    }

    @Test
    void verifiedFreshPeerDeviceKeyEvidenceYieldsHardwareBasis() {
        SessionDeviceTrustVerifier.DeviceTrustDecision d =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session("peer-1", TENANT, "dev-1"), trust(true), NOW);

        assertTrue(d.trusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.HARDWARE_KEY_ATTESTATION, d.basis());
        assertEquals("hardware-key-attestation-verified", d.reason());
    }

    @Test
    void missingPeerTrustIsFailClosed() {
        SessionDeviceTrustVerifier.DeviceTrustDecision d =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session("peer-1", TENANT, "dev-1"), null, NOW);

        assertFalse(d.trusted());
        assertEquals("no-fresh-peer-trust", d.reason());
    }

    @Test
    void unverifiedDeviceKeyEvidenceIsFailClosed() {
        SessionDeviceTrustVerifier.DeviceTrustDecision d =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session("peer-1", TENANT, "dev-1"), trust(false), NOW);

        assertFalse(d.trusted());
        assertEquals("hardware-key-attestation-unverified", d.reason());
    }

    @Test
    void missingSessionIdentityIsFailClosed() {
        SessionDeviceTrustVerifier.DeviceTrustDecision d =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session("peer-1", TENANT, ""), trust(true), NOW);

        assertFalse(d.trusted());
        assertEquals("missing-session-identity", d.reason());
    }

    @Test
    void certBoundDeviceIdentityMismatchIsFailClosedInsideTheVerifier() {
        SessionDeviceTrustVerifier.DeviceTrustDecision d =
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE
                        .verify(session("peer-1", TENANT, "dev-OTHER"), trust(true), NOW);

        assertFalse(d.trusted());
        assertEquals("device-identity-mismatch", d.reason());
    }
}

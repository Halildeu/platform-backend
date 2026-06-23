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

class CompositeSessionDeviceTrustVerifierTest {

    private static final long NOW = 1_000_000L;
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";

    private static RemoteBridgeSession session() {
        return new RemoteBridgeSession("s1", "peer-1", "dev-1", "operator@x", TENANT, "Operator X",
                Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 60_000L, NOW, State.ACTIVE);
    }

    private static PeerTrust trust(boolean deviceTrusted) {
        return new PeerTrust(true, true, deviceTrusted, Optional.of("dev-1"), "dev-1", NOW);
    }

    @Test
    void enrollmentAndHardwareKeyBothRequired() {
        CompositeSessionDeviceTrustVerifier verifier = new CompositeSessionDeviceTrustVerifier(
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.enrolledActive(),
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE);

        SessionDeviceTrustVerifier.DeviceTrustDecision d = verifier.verify(session(), trust(true), NOW);

        assertTrue(d.trusted());
        assertEquals(SessionDeviceTrustVerifier.Basis.COMPOSITE, d.basis());
        assertEquals("enrollment-and-hardware-key-attested", d.reason());
    }

    @Test
    void enrollmentFailureDeniesBeforeHardwareTrust() {
        CompositeSessionDeviceTrustVerifier verifier = new CompositeSessionDeviceTrustVerifier(
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.deny(
                        "no-active-enrolled-connected-peer"),
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE);

        SessionDeviceTrustVerifier.DeviceTrustDecision d = verifier.verify(session(), trust(true), NOW);

        assertFalse(d.trusted());
        assertEquals("enrollment-no-active-enrolled-connected-peer", d.reason());
    }

    @Test
    void hardwareFailureDeniesEvenWhenEnrollmentPasses() {
        CompositeSessionDeviceTrustVerifier verifier = new CompositeSessionDeviceTrustVerifier(
                (s, t, now) -> SessionDeviceTrustVerifier.DeviceTrustDecision.enrolledActive(),
                PeerDeviceKeyAttestationSessionDeviceTrustVerifier.INSTANCE);

        SessionDeviceTrustVerifier.DeviceTrustDecision d = verifier.verify(session(), trust(false), NOW);

        assertFalse(d.trusted());
        assertEquals("hardware-hardware-key-attestation-unverified", d.reason());
    }
}

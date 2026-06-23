package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;

import java.util.Objects;

/**
 * Faz 22.6 B1.4 (#548) — production-shaped device trust requires both identity binding and hardware binding:
 * active machine-cert enrollment for the requested tenant/device, and verified device-key / TPM attestation on
 * the same fresh peer-trust record. Either side failing keeps device trust false.
 */
public final class CompositeSessionDeviceTrustVerifier implements SessionDeviceTrustVerifier {

    private final SessionDeviceTrustVerifier enrollmentVerifier;
    private final SessionDeviceTrustVerifier hardwareVerifier;

    public CompositeSessionDeviceTrustVerifier(SessionDeviceTrustVerifier enrollmentVerifier,
                                               SessionDeviceTrustVerifier hardwareVerifier) {
        this.enrollmentVerifier = Objects.requireNonNull(enrollmentVerifier, "enrollmentVerifier");
        this.hardwareVerifier = Objects.requireNonNull(hardwareVerifier, "hardwareVerifier");
    }

    @Override
    public DeviceTrustDecision verify(RemoteBridgeSession session, PeerTrust peerTrust, long nowEpochMillis) {
        DeviceTrustDecision enrollment = enrollmentVerifier.verify(session, peerTrust, nowEpochMillis);
        if (enrollment == null || !enrollment.trusted()) {
            return DeviceTrustDecision.deny(prefix("enrollment", enrollment));
        }
        DeviceTrustDecision hardware = hardwareVerifier.verify(session, peerTrust, nowEpochMillis);
        if (hardware == null || !hardware.trusted()) {
            return DeviceTrustDecision.deny(prefix("hardware", hardware));
        }
        return DeviceTrustDecision.enrollmentAndHardwareKeyAttested();
    }

    private static String prefix(String label, DeviceTrustDecision decision) {
        String reason = decision == null ? "" : decision.reason();
        if (reason == null || reason.isBlank()) {
            return label + "-device-trust-denied";
        }
        String normalized = label + "-" + reason.trim();
        return normalized.matches("^[a-z0-9-]{1,64}$")
                ? normalized
                : label + "-device-trust-denied";
    }
}

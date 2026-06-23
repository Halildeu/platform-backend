package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;

/**
 * Faz 22.6 B1.4 (#548) — promotes the peer-trust ledger's verified device-key attestation into the session
 * device-trust seam. The ledger has already run {@code DeviceIdentityVerifier} over transport-bound
 * AgentHello evidence; this class only consumes that fail-closed result.
 *
 * <p>Missing/stale peer trust, a session without identity, or unverified device-key evidence denies trust. This
 * is source wiring, not #548 closure: live TPM/provisioning and negative evidence still have to be accepted.
 */
public final class PeerDeviceKeyAttestationSessionDeviceTrustVerifier implements SessionDeviceTrustVerifier {

    public static final PeerDeviceKeyAttestationSessionDeviceTrustVerifier INSTANCE =
            new PeerDeviceKeyAttestationSessionDeviceTrustVerifier();

    private PeerDeviceKeyAttestationSessionDeviceTrustVerifier() {
    }

    @Override
    public DeviceTrustDecision verify(RemoteBridgeSession session, PeerTrust peerTrust, long nowEpochMillis) {
        if (session == null) {
            return DeviceTrustDecision.deny("missing-session");
        }
        if (isBlank(session.transportPeerKey()) || isBlank(session.deviceId())
                || isBlank(session.operatorTenantId())) {
            return DeviceTrustDecision.deny("missing-session-identity");
        }
        if (peerTrust == null) {
            return DeviceTrustDecision.deny("no-fresh-peer-trust");
        }
        if (!TrustEvidenceAssembler.deviceIdentitiesConsistent(peerTrust, session.deviceId())) {
            return DeviceTrustDecision.deny("device-identity-mismatch");
        }
        return peerTrust.deviceTrusted()
                ? DeviceTrustDecision.hardwareKeyAttested()
                : DeviceTrustDecision.deny("hardware-key-attestation-unverified");
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}

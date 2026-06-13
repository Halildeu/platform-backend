package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;

/**
 * Faz 22.6 D10.1 slice-3b (#634) — the fail-closed default {@link SessionDeviceTrustVerifier}: device trust is
 * never established, so {@code deviceTrusted} stays false and the broker stays gated. This is the behaviour
 * before a pilot opts into a real device-trust basis (and the safe fallback if no verifier is wired).
 */
public final class DenyAllSessionDeviceTrustVerifier implements SessionDeviceTrustVerifier {

    public static final DenyAllSessionDeviceTrustVerifier INSTANCE = new DenyAllSessionDeviceTrustVerifier();

    private DenyAllSessionDeviceTrustVerifier() {
    }

    @Override
    public DeviceTrustDecision verify(RemoteBridgeSession session, PeerTrust peerTrust, long nowEpochMillis) {
        return DeviceTrustDecision.deny("device-trust-not-configured");
    }
}

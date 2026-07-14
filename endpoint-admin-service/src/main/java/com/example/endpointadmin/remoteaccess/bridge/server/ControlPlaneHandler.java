package com.example.endpointadmin.remoteaccess.bridge.server;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the seam between the transport layer and the broker control-plane. The
 * T-2b service decodes inbound CONTROL payloads to DOMAIN records (via the T-2a adapter) and hands them here
 * together with the AUTHENTICATED {@link PeerIdentity} — it deliberately does NOT call
 * {@code RemoteBridgeBroker} yet: assembling the real {@code RemoteBridgeTrustEvidence} (B1.4 verifiers,
 * WebAuthn step-up, duress, consent lease, owner token) is the owner-gated T-4 wiring (ADR-0034 §13/D10).
 *
 * <p>{@link #INERT} is the default (and the only) T-2b implementation: it acknowledges nothing, authorizes
 * nothing, and persists nothing — the transport slice stays policy-free.
 */
public interface ControlPlaneHandler {

    /** Advisory hello from an authenticated peer (still NEVER an authorization input). */
    void onAgentHello(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello);

    /**
     * Authenticated CONTROL liveness from a peer. A heartbeat is never authority by itself; orchestrators may use
     * it only to re-evaluate previously presented peer evidence against the current authenticated transport.
     */
    default void onHeartbeat(PeerIdentity peer) {
    }

    /**
     * The authenticated peer's current CONTROL stream ended without the broker first removing it for an
     * explicit KILL/replacement. Implementations must treat this as loss of the transport safety lease; it is
     * never an authorization signal. The transport invokes this only after an identity-checked conditional
     * registry removal, so a late callback from a replaced stream cannot terminate its successor.
     */
    default void onControlStreamClosed(PeerIdentity peer) {
    }

    /** The endpoint user's consent outcome, reported by the agent. */
    void onConsentResult(PeerIdentity peer, RemoteBridgeMessages.ConsentResult result);

    /** Agent-originated control-plane audit metadata (content hash, never raw payload). */
    void onAuditEvent(PeerIdentity peer, RemoteBridgeMessages.AuditEvent event);

    /**
     * Agent-originated diagnostic frame for a local dispatch/transport failure. This is observability only: it
     * must not authorize, retry, or mutate policy state by itself.
     */
    default void onAgentErrorFrame(PeerIdentity peer, RemoteBridgeMessages.AgentErrorFrame error) {
    }

    /**
     * Agent-originated fresh device-key session attestation (Faz 22.6 #548 Path A) answering a broker
     * {@code DeviceKeyChallenge}. Seam/observability only in this slice — the {@code DEVICE_KEY_ATTESTATION_REAL}
     * verifier (a later slice) consumes it to decide device trust; by itself it authorizes nothing.
     */
    default void onDeviceKeyAttestationResponse(PeerIdentity peer,
                                                RemoteBridgeMessages.DeviceKeyAttestationResponse response) {
    }

    /** T-2b default: accept-and-ignore. Real control-plane wiring is T-4. */
    ControlPlaneHandler INERT = new ControlPlaneHandler() {
        @Override
        public void onAgentHello(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello) {
        }

        @Override
        public void onConsentResult(PeerIdentity peer, RemoteBridgeMessages.ConsentResult result) {
        }

        @Override
        public void onAuditEvent(PeerIdentity peer, RemoteBridgeMessages.AuditEvent event) {
        }
    };
}

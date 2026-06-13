package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.CertThumbprint;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.PeerTrustLedger.PeerTrust;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.6 D10.1 slice-3b (#634, Codex 019ec29a — Path B) — the pilot {@link SessionDeviceTrustVerifier}:
 * {@code deviceTrusted} = the live session peer IS the active enrolled machine certificate for the operator's
 * tenant + the requested device. It reuses {@link ConnectedDeviceResolver} (so it inherits every fail-closed
 * enrollment gate — tenant-scoped single active cert, validity window, device eligibility, canonical thumbprint,
 * a currently-registered live peer) and adds the load-bearing check: the resolved enrolled peer's
 * {@code transportPeerKey} MUST equal THIS session's {@code transportPeerKey} (so a session cannot borrow another
 * device's enrollment). Because the resolve is fresh per call, a cert revoked/expired after openSession, or a
 * dropped agent, fails here at operation time.
 *
 * <p><b>This is device ENROLLMENT identity, NOT hardware key attestation</b> (Codex): it proves the peer is the
 * enrolled active machine cert, NOT that the key lives in a TPM/secure element. That stronger claim is the future
 * {@code DeviceKeyAttestation} path ({@link Basis#HARDWARE_KEY_ATTESTATION}). The decision's basis says
 * {@link Basis#MACHINE_CERT_ENROLLMENT} so the broker/audit never overclaim. A non-prod pilot basis — the factory
 * forbids it in a prod-like profile until the policy/D29-EA explicitly accepts enrollment-only device trust.
 */
public final class MachineCertEnrollmentDeviceTrustVerifier implements SessionDeviceTrustVerifier {

    private final ConnectedDeviceResolver resolver;

    public MachineCertEnrollmentDeviceTrustVerifier(ConnectedDeviceResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public DeviceTrustDecision verify(RemoteBridgeSession session, PeerTrust peerTrust, long nowEpochMillis) {
        if (session == null) {
            return DeviceTrustDecision.deny("missing-session");
        }
        String peerKey = session.transportPeerKey();
        String tenantRaw = session.operatorTenantId();
        String deviceRaw = session.deviceId();
        if (isBlank(peerKey) || isBlank(tenantRaw) || isBlank(deviceRaw)) {
            return DeviceTrustDecision.deny("missing-session-identity");
        }
        UUID tenant = parseCanonicalUuid(tenantRaw);
        UUID device = parseCanonicalUuid(deviceRaw);
        if (tenant == null || device == null) {
            return DeviceTrustDecision.deny("non-canonical-tenant-or-device");
        }
        try {
            Optional<PeerIdentity> connected =
                    resolver.resolveConnectedPeer(tenant, device, Instant.ofEpochMilli(nowEpochMillis));
            if (connected.isEmpty()) {
                // no eligible + in-window + active enrolled cert with a live registered peer — fail-closed
                return DeviceTrustDecision.deny("no-active-enrolled-connected-peer");
            }
            // the enrolled device's live peer MUST be exactly this session's authenticated peer
            boolean samePeer = CertThumbprint.matches(connected.get().transportPeerKey(), peerKey);
            return samePeer
                    ? DeviceTrustDecision.enrolledActive()
                    : DeviceTrustDecision.deny("transport-peer-mismatch");
        } catch (RuntimeException resolverOrRepositoryError) {
            // total + fail-closed: a resolver/repository failure is never device trust, never a propagated throw
            return DeviceTrustDecision.deny("device-trust-check-error");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Strict canonical-UUID parse ({@code UUID.fromString} is lenient on case/spelling); non-canonical → null. */
    private static UUID parseCanonicalUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            return parsed.toString().equals(value) ? parsed : null;
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}

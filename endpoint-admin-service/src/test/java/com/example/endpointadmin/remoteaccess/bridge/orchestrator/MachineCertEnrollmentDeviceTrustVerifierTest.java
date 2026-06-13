package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier.Basis;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.SessionDeviceTrustVerifier.DeviceTrustDecision;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Faz 22.6 D10.1 slice-3b (#634, Codex 019ec29a — Path B) — the {@link MachineCertEnrollmentDeviceTrustVerifier}
 * acceptance matrix. It trusts ONLY a session whose authenticated peer IS the active enrolled machine cert for the
 * operator's tenant + the requested device (via {@link ConnectedDeviceResolver}); everything else fails closed.
 * The resolver's own fail-closed gates (revoked/expired/ineligible/cross-tenant cert) are covered by
 * {@code ConnectedDeviceResolverTest} — here they all manifest as "resolver returns empty → deny".
 */
class MachineCertEnrollmentDeviceTrustVerifierTest {

    private static final long NOW = 1_000_000L;
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String DEVICE = "22222222-2222-2222-2222-222222222222";
    private static final UUID TENANT_UUID = UUID.fromString(TENANT);
    private static final UUID DEVICE_UUID = UUID.fromString(DEVICE);
    // 64-hex transport keys (the lowercase SHA-256-DER thumbprint form CertThumbprint compares)
    private static final String PEER_KEY = "ab".repeat(32);
    private static final String OTHER_KEY = "cd".repeat(32);

    private static RemoteBridgeSession session(String peerKey, String tenant, String deviceId) {
        return new RemoteBridgeSession("s1", peerKey, deviceId, "operator@x", tenant, "Operator X",
                java.util.Set.of(RemoteSessionCapability.VIEW_ONLY), NOW + 60_000L, NOW, State.ACTIVE);
    }

    private static PeerIdentity peer(String transportPeerKey) {
        return new PeerIdentity(transportPeerKey, Optional.of(DEVICE), List.of());
    }

    @Test
    void anEnrolledActivePeerMatchingTheSessionIsTrusted() {
        ConnectedDeviceResolver resolver = mock(ConnectedDeviceResolver.class);
        when(resolver.resolveConnectedPeer(eq(TENANT_UUID), eq(DEVICE_UUID), any(Instant.class)))
                .thenReturn(Optional.of(peer(PEER_KEY)));

        DeviceTrustDecision d = new MachineCertEnrollmentDeviceTrustVerifier(resolver)
                .verify(session(PEER_KEY, TENANT, DEVICE), null, NOW);

        assertTrue(d.trusted());
        assertEquals(Basis.MACHINE_CERT_ENROLLMENT, d.basis(), "enrollment basis — NOT hardware key attestation");
        assertEquals("enrolled-active-machine-cert", d.reason());
    }

    @Test
    void aResolvedPeerWithADifferentThumbprintIsRejected() {
        ConnectedDeviceResolver resolver = mock(ConnectedDeviceResolver.class);
        // the enrolled device's live peer is a DIFFERENT cert than this session's peer — a borrowed enrollment
        when(resolver.resolveConnectedPeer(any(), any(), any())).thenReturn(Optional.of(peer(OTHER_KEY)));

        DeviceTrustDecision d = new MachineCertEnrollmentDeviceTrustVerifier(resolver)
                .verify(session(PEER_KEY, TENANT, DEVICE), null, NOW);

        assertFalse(d.trusted());
        assertEquals(Basis.NONE, d.basis());
        assertEquals("transport-peer-mismatch", d.reason());
    }

    @Test
    void noEnrolledConnectedPeerIsRejected() {
        ConnectedDeviceResolver resolver = mock(ConnectedDeviceResolver.class);
        // covers every resolver fail-closed gate (revoked/expired/ineligible/cross-tenant/dropped peer)
        when(resolver.resolveConnectedPeer(any(), any(), any())).thenReturn(Optional.empty());

        DeviceTrustDecision d = new MachineCertEnrollmentDeviceTrustVerifier(resolver)
                .verify(session(PEER_KEY, TENANT, DEVICE), null, NOW);

        assertFalse(d.trusted());
        assertEquals("no-active-enrolled-connected-peer", d.reason());
    }

    @Test
    void aNullSessionIsRejected() {
        DeviceTrustDecision d = new MachineCertEnrollmentDeviceTrustVerifier(mock(ConnectedDeviceResolver.class))
                .verify(null, null, NOW);
        assertFalse(d.trusted());
        assertEquals("missing-session", d.reason());
    }

    @Test
    void aBlankSessionIdentityIsRejected() {
        MachineCertEnrollmentDeviceTrustVerifier verifier =
                new MachineCertEnrollmentDeviceTrustVerifier(mock(ConnectedDeviceResolver.class));
        assertEquals("missing-session-identity",
                verifier.verify(session("", TENANT, DEVICE), null, NOW).reason());
        assertEquals("missing-session-identity",
                verifier.verify(session(PEER_KEY, "", DEVICE), null, NOW).reason());
        assertEquals("missing-session-identity",
                verifier.verify(session(PEER_KEY, TENANT, ""), null, NOW).reason());
    }

    @Test
    void aNonCanonicalTenantOrDeviceIsRejected() {
        MachineCertEnrollmentDeviceTrustVerifier verifier =
                new MachineCertEnrollmentDeviceTrustVerifier(mock(ConnectedDeviceResolver.class));
        assertEquals("non-canonical-tenant-or-device",
                verifier.verify(session(PEER_KEY, "NOT-A-UUID", DEVICE), null, NOW).reason());
        assertEquals("non-canonical-tenant-or-device",
                verifier.verify(session(PEER_KEY, TENANT, "1111"), null, NOW).reason());
        // UUID.fromString is lenient on case; we require an exact canonical round-trip → an uppercase (letter-
        // bearing) UUID is rejected. NOTE: a letter-bearing value is essential — the all-digit TENANT's toUpperCase
        // is a no-op (would spuriously pass), so this uses an explicit uppercase letter UUID.
        assertEquals("non-canonical-tenant-or-device",
                verifier.verify(session(PEER_KEY, "AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA", DEVICE), null, NOW)
                        .reason());
    }

    @Test
    void aResolverExceptionIsFailClosed() {
        ConnectedDeviceResolver resolver = mock(ConnectedDeviceResolver.class);
        when(resolver.resolveConnectedPeer(any(), any(), any())).thenThrow(new RuntimeException("db down"));

        DeviceTrustDecision d = new MachineCertEnrollmentDeviceTrustVerifier(resolver)
                .verify(session(PEER_KEY, TENANT, DEVICE), null, NOW);

        assertFalse(d.trusted(), "a resolver/repository failure is never device trust");
        assertEquals("device-trust-check-error", d.reason());
    }

    @Test
    void aNullResolverIsRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new MachineCertEnrollmentDeviceTrustVerifier(null));
    }
}

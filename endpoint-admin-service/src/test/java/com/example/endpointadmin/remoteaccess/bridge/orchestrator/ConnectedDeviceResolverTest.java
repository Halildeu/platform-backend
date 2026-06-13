package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Faz 22.6 slice-4c-2b-2a (Codex 019ebe06) — the device→peer resolver is fail-closed at every gate: only an
 * in-window, active cert for an eligible device whose live stream is registered yields the peer; every other
 * path is empty AND never touches the registry. The registry is mocked here (its own register/connectedPeer
 * behaviour is covered by {@code ControlStreamRegistryTest}); this proves the resolver's validation contract.
 */
class ConnectedDeviceResolverTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID DEVICE = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final String THUMBPRINT = "a".repeat(64); // canonical lowercase 64-hex
    private static final Instant NOW = Instant.parse("2026-06-13T12:00:00Z");

    private final EndpointMachineCertRepository certs = mock(EndpointMachineCertRepository.class);
    private final ControlStreamRegistry registry = mock(ControlStreamRegistry.class);
    private final ConnectedDeviceResolver resolver = new ConnectedDeviceResolver(certs, registry);

    private EndpointMachineCert cert(String thumbprint, Instant notBefore, Instant notAfter, DeviceStatus status) {
        EndpointDevice device = mock(EndpointDevice.class);
        when(device.getStatus()).thenReturn(status);
        EndpointMachineCert cert = mock(EndpointMachineCert.class);
        when(cert.getCertThumbprint()).thenReturn(thumbprint);
        when(cert.getCertNotBefore()).thenReturn(notBefore);
        when(cert.getCertNotAfter()).thenReturn(notAfter);
        when(cert.getDevice()).thenReturn(device);
        return cert;
    }

    private EndpointMachineCert validCert() {
        return cert(THUMBPRINT, NOW.minusSeconds(3600), NOW.plusSeconds(3600), DeviceStatus.ONLINE);
    }

    private void activeCertIs(EndpointMachineCert cert) {
        when(certs.findActiveByTenantIdAndDeviceId(TENANT, DEVICE)).thenReturn(Optional.ofNullable(cert));
    }

    @Test
    void anEligibleActiveInWindowCertWithALiveStreamResolvesTheRegisteredPeer() {
        PeerIdentity peer = new PeerIdentity(THUMBPRINT, Optional.empty(), List.of());
        activeCertIs(validCert());
        when(registry.connectedPeer(THUMBPRINT)).thenReturn(Optional.of(peer));

        Optional<PeerIdentity> resolved = resolver.resolveConnectedPeer(TENANT, DEVICE, NOW);

        assertTrue(resolved.isPresent());
        assertEquals(peer, resolved.get());
        verify(registry).connectedPeer(THUMBPRINT);
    }

    @Test
    void anUppercaseThumbprintIsCanonicalizedToLowercaseBeforeTheRegistryLookup() {
        activeCertIs(cert(THUMBPRINT.toUpperCase(), NOW.minusSeconds(60), NOW.plusSeconds(60), DeviceStatus.STALE));
        when(registry.connectedPeer(THUMBPRINT)).thenReturn(Optional.empty());

        resolver.resolveConnectedPeer(TENANT, DEVICE, NOW);

        verify(registry).connectedPeer(THUMBPRINT); // the lowercase canonical form, never the uppercase
    }

    @Test
    void aResolvedButDroppedPeerIsEmpty() {
        activeCertIs(validCert());
        when(registry.connectedPeer(THUMBPRINT)).thenReturn(Optional.empty());
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
    }

    // ---- fail-closed gates: each is empty AND never reaches the registry ----

    @Test
    void aNullTenantDeviceOrNowIsEmpty() {
        assertTrue(resolver.resolveConnectedPeer(null, DEVICE, NOW).isEmpty());
        assertTrue(resolver.resolveConnectedPeer(TENANT, null, NOW).isEmpty());
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, null).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void noActiveCertForTheTenantAndDeviceIsEmpty() {
        activeCertIs(null); // tenant-scoped + revoked-null query found nothing (cross-tenant / revoked / unknown)
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void anExpiredCertIsEmpty() {
        activeCertIs(cert(THUMBPRINT, NOW.minusSeconds(7200), NOW.minusSeconds(60), DeviceStatus.ONLINE));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void aNotYetValidCertIsEmpty() {
        activeCertIs(cert(THUMBPRINT, NOW.plusSeconds(60), NOW.plusSeconds(7200), DeviceStatus.ONLINE));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void aNullValidityWindowIsEmpty() {
        activeCertIs(cert(THUMBPRINT, null, NOW.plusSeconds(60), DeviceStatus.ONLINE));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        activeCertIs(cert(THUMBPRINT, NOW.minusSeconds(60), null, DeviceStatus.ONLINE));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void aDecommissionedOrPendingOrNullStatusDeviceIsEmpty() {
        activeCertIs(cert(THUMBPRINT, NOW.minusSeconds(60), NOW.plusSeconds(60), DeviceStatus.DECOMMISSIONED));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        activeCertIs(cert(THUMBPRINT, NOW.minusSeconds(60), NOW.plusSeconds(60), DeviceStatus.PENDING_ENROLLMENT));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        activeCertIs(cert(THUMBPRINT, NOW.minusSeconds(60), NOW.plusSeconds(60), null));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void aNullDeviceIsEmpty() {
        EndpointMachineCert cert = mock(EndpointMachineCert.class);
        when(cert.getCertThumbprint()).thenReturn(THUMBPRINT);
        when(cert.getCertNotBefore()).thenReturn(NOW.minusSeconds(60));
        when(cert.getCertNotAfter()).thenReturn(NOW.plusSeconds(60));
        when(cert.getDevice()).thenReturn(null);
        activeCertIs(cert);
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void aNonCanonicalThumbprintIsEmpty() {
        activeCertIs(cert("not-hex", NOW.minusSeconds(60), NOW.plusSeconds(60), DeviceStatus.ONLINE));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        activeCertIs(cert("a".repeat(63), NOW.minusSeconds(60), NOW.plusSeconds(60), DeviceStatus.ONLINE)); // 63 chars
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        activeCertIs(cert(null, NOW.minusSeconds(60), NOW.plusSeconds(60), DeviceStatus.ONLINE));
        assertTrue(resolver.resolveConnectedPeer(TENANT, DEVICE, NOW).isEmpty());
        verify(registry, never()).connectedPeer(anyString());
    }

    @Test
    void theTenantScopedQueryIsUsedNotADeviceOnlyLookup() {
        activeCertIs(validCert());
        when(registry.connectedPeer(anyString())).thenReturn(Optional.empty());
        resolver.resolveConnectedPeer(TENANT, DEVICE, NOW);
        // the data-access boundary is tenant-scoped (no device-only query, no cross-tenant materialization)
        verify(certs).findActiveByTenantIdAndDeviceId(eq(TENANT), eq(DEVICE));
        verify(certs, never()).findActiveByDeviceId(any());
    }
}

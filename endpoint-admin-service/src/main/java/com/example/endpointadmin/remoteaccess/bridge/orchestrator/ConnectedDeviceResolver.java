package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import com.example.endpointadmin.model.EndpointMachineCert;
import com.example.endpointadmin.remoteaccess.bridge.server.ControlStreamRegistry;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import com.example.endpointadmin.repository.EndpointMachineCertRepository;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Faz 22.6 slice-4c-2b-2a (Codex 019ebe06) — resolves an operator-supplied {@code (tenantId, deviceId)} to the
 * connected agent {@link PeerIdentity}, the verified device→peer mapping {@code openSession} needs. Lazy, not a
 * connect-time index: a device's single ACTIVE cert thumbprint EQUALS the agent's {@code transportPeerKey}
 * (both are the lowercase SHA-256 hex of the leaf DER), so the resolve is
 * {@code (tenant, deviceId) → active cert → thumbprint → connectedPeer} — fresh on every call, so a
 * revoked/expired cert or a dropped peer is caught at open time, not cached.
 *
 * <p><b>Fail-closed, every gate (Codex REVISE):</b>
 * <ul>
 *   <li>null tenant/device/now ⇒ empty.</li>
 *   <li>The cert query is tenant-scoped + {@code revoked_at IS NULL} + single-active (partial unique index), so
 *       a cross-tenant device, a revoked cert, or an unknown device yields no row ⇒ empty.</li>
 *   <li>The cert validity window is enforced null-safe: {@code notBefore <= now < notAfter} ⇒ otherwise empty.</li>
 *   <li>The device must be eligible — a null device/status, {@code PENDING_ENROLLMENT}, or
 *       {@code DECOMMISSIONED} ⇒ empty (a not-yet-enrolled or retired device is never a remote-session target).</li>
 *   <li>The stored thumbprint must canonicalize to lowercase 64-hex; otherwise it cannot equal a transport key
 *       ⇒ empty.</li>
 *   <li>Finally only a still-registered peer is returned; a dropped agent ⇒ empty (no session opens to a gone
 *       device).</li>
 * </ul>
 *
 * <p>It mints NO authority and trusts NO client field — the tenant is the operator's verified tenant (parsed
 * upstream) and the deviceId is matched against the data plane, never used to fabricate a peer.
 */
public final class ConnectedDeviceResolver {

    private static final Pattern CANONICAL_THUMBPRINT = Pattern.compile("[0-9a-f]{64}");
    /** A device that is not (yet) enrolled, or retired, may never be the target of a remote session. */
    private static final Set<DeviceStatus> INELIGIBLE_STATUSES =
            Set.of(DeviceStatus.PENDING_ENROLLMENT, DeviceStatus.DECOMMISSIONED);

    private final EndpointMachineCertRepository certs;
    private final ControlStreamRegistry registry;

    public ConnectedDeviceResolver(EndpointMachineCertRepository certs, ControlStreamRegistry registry) {
        this.certs = Objects.requireNonNull(certs, "certs");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    /**
     * The connected agent peer for the operator's own tenant + the requested device, or empty if there is no
     * eligible, in-window, active cert whose live stream is currently registered.
     */
    public Optional<PeerIdentity> resolveConnectedPeer(UUID tenantId, UUID deviceId, Instant now) {
        if (tenantId == null || deviceId == null || now == null) {
            return Optional.empty();
        }
        EndpointMachineCert cert = certs.findActiveByTenantIdAndDeviceId(tenantId, deviceId).orElse(null);
        if (cert == null) {
            return Optional.empty(); // no active cert for this device in this tenant (or cross-tenant) — fail-closed
        }
        if (!withinValidity(cert, now)) {
            return Optional.empty(); // expired / not-yet-valid / null window — fail-closed
        }
        if (!deviceEligible(cert, tenantId)) {
            return Optional.empty(); // null device/status, cross-tenant device, PENDING_ENROLLMENT, or DECOMMISSIONED
        }
        String thumbprint = canonicalThumbprint(cert.getCertThumbprint());
        if (thumbprint == null) {
            return Optional.empty(); // a non-canonical thumbprint can never equal a transport key — fail-closed
        }
        // the device's active-cert thumbprint == the connected peer's transportPeerKey; a live peer, or empty
        return registry.connectedPeer(thumbprint);
    }

    private static boolean withinValidity(EndpointMachineCert cert, Instant now) {
        Instant notBefore = cert.getCertNotBefore();
        Instant notAfter = cert.getCertNotAfter();
        return notBefore != null && notAfter != null
                && !now.isBefore(notBefore) && now.isBefore(notAfter);
    }

    private static boolean deviceEligible(EndpointMachineCert cert, UUID tenantId) {
        EndpointDevice device = cert.getDevice();
        if (device == null || device.getStatus() == null) {
            return false;
        }
        // defense-in-depth (Codex REVISE): device_id is a single-column FK, so a corrupt/raced row could pair a
        // tenant-A cert with a tenant-B device — re-check the device's own tenant even though the query pins it
        if (!Objects.equals(device.getTenantId(), tenantId)) {
            return false;
        }
        return !INELIGIBLE_STATUSES.contains(device.getStatus());
    }

    private static String canonicalThumbprint(String raw) {
        if (raw == null) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        return CANONICAL_THUMBPRINT.matcher(normalized).matches() ? normalized : null;
    }
}

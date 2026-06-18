package com.example.endpointadmin.remoteaccess.bridge.server;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Faz 22.6 T-2b (Codex 019eb9fb) — the AUTHENTICATED transport identity of a connected agent, derived by
 * {@link PeerIdentityInterceptor} from the mTLS peer certificate — NEVER from {@code AgentHello} (which is
 * advisory-only). This is <b>transport identity evidence, not device trust</b>: B1.4's verifiers still decide
 * {@code deviceTrusted}; this only answers "which TLS client is this stream?".
 *
 * @param transportPeerKey  stable key for the authenticated peer (SHA-256 fingerprint of the leaf cert) —
 *                          the {@link ControlStreamRegistry} key, so an agent can never claim another
 *                          device's registry slot by lying in {@code AgentHello.deviceId}
 * @param certBoundDeviceId the device id carried INSIDE the authenticated certificate (SAN), when present —
 *                          cert-derived, never hello-derived
 * @param certBoundAdComputerId the AD computer objectGUID carried INSIDE the authenticated certificate
 *                              ({@code adcomputer:{objectGUID}} SAN URI), when present — cert-derived,
 *                              never hello-derived
 * @param chain             the presented certificate chain (held for the B1.4 verifiers downstream; never
 *                          logged raw)
 */
public record PeerIdentity(String transportPeerKey,
                           Optional<String> certBoundDeviceId,
                           Optional<String> certBoundAdComputerId,
                           List<X509Certificate> chain) {

    public PeerIdentity(String transportPeerKey,
                        Optional<String> certBoundDeviceId,
                        List<X509Certificate> chain) {
        this(transportPeerKey, certBoundDeviceId, Optional.empty(), chain);
    }

    public PeerIdentity {
        if (transportPeerKey == null || transportPeerKey.isBlank()) {
            throw new IllegalArgumentException("transportPeerKey is required");
        }
        certBoundDeviceId = certBoundDeviceId == null ? Optional.empty() : certBoundDeviceId;
        certBoundAdComputerId = normalizeAdComputerId(certBoundAdComputerId);
        chain = chain == null ? List.of() : List.copyOf(chain);
    }

    private static Optional<String> normalizeAdComputerId(Optional<String> raw) {
        if (raw == null || raw.isEmpty() || raw.get().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(raw.get().trim()).toString());
        } catch (IllegalArgumentException ignored) {
            return Optional.empty();
        }
    }
}

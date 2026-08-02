package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Faz 22.6 T-4a-i (Codex 019ebbfa P3) — per-authenticated-peer verifier outcomes. On every AgentHello the
 * B1.4 verifiers run over the PARSED evidence ({@link PeerEvidenceParser}) and the resulting booleans are
 * recorded with a timestamp; consumers read through {@link #fresh}, which returns empty once the record is
 * older than the freshness TTL — a stale verification is NO verification (the re-verify/freshness rule).
 *
 * <p>Keyed by {@link PeerIdentity#transportPeerKey()} (the mTLS leaf fingerprint) — NEVER by an advisory
 * hello field. The booleans here become {@code RemoteBridgeTrustEvidence}'s cert/attestation/device inputs
 * at operation time (T-4a-ii); a peer with no fresh ledger entry gets no consent prompt and no permit.
 */
public final class PeerTrustLedger {

    /** A point-in-time verifier outcome for one authenticated peer. */
    public record PeerTrust(boolean certTrusted,
                            boolean attestationVerified,
                            boolean deviceTrusted,
                            Optional<String> certBoundDeviceId,
                            String helloDeviceId,
                            long recordedAtEpochMillis,
                            Set<String> supportedFeatures) {
        public PeerTrust {
            supportedFeatures = supportedFeatures == null ? Set.of() : Set.copyOf(supportedFeatures);
        }

        public PeerTrust(boolean certTrusted, boolean attestationVerified, boolean deviceTrusted,
                         Optional<String> certBoundDeviceId, String helloDeviceId,
                         long recordedAtEpochMillis) {
            this(certTrusted, attestationVerified, deviceTrusted, certBoundDeviceId, helloDeviceId,
                    recordedAtEpochMillis, Set.of());
        }
    }

    private final CertTrustEvaluator certTrustEvaluator;
    private final AttestationVerifier attestationVerifier;
    private final DeviceIdentityVerifier deviceIdentityVerifier;
    private final PeerEvidenceParser parser;
    private final long freshnessTtlMillis;
    private final Map<String, PeerTrust> byPeer = new ConcurrentHashMap<>();

    public PeerTrustLedger(CertTrustEvaluator certTrustEvaluator,
                           AttestationVerifier attestationVerifier,
                           DeviceIdentityVerifier deviceIdentityVerifier,
                           PeerEvidenceParser parser,
                           long freshnessTtlMillis) {
        if (certTrustEvaluator == null || attestationVerifier == null || deviceIdentityVerifier == null
                || parser == null) {
            throw new IllegalArgumentException("all verifiers and the parser are required");
        }
        if (freshnessTtlMillis <= 0) {
            throw new IllegalArgumentException("freshnessTtlMillis must be positive");
        }
        this.certTrustEvaluator = certTrustEvaluator;
        this.attestationVerifier = attestationVerifier;
        this.deviceIdentityVerifier = deviceIdentityVerifier;
        this.parser = parser;
        this.freshnessTtlMillis = freshnessTtlMillis;
    }

    /**
     * Run the verifiers over the parsed evidence and record the outcome. Absent evidence verifies to FALSE
     * (never skipped-as-true); a verifier throwing records FALSE for that dimension (fail-closed, total).
     */
    public PeerTrust record(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello, long nowEpochMillis) {
        PeerTrust trust = evaluate(peer, hello, nowEpochMillis);
        publish(peer, trust);
        return trust;
    }

    /**
     * Run the verifier composition without publishing a peer-wide trust record. Consent handling uses this
     * two-phase form so verifier work does not block a session KILL, while publication can still be committed
     * atomically with that same session incarnation's CONSENT_GRANTED transition.
     */
    PeerTrust evaluate(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello, long nowEpochMillis) {
        return evaluate(peer, hello, () -> nowEpochMillis);
    }

    /**
     * Evaluate against a live clock. Verification can include bounded certificate/attestation lookups, so using a
     * timestamp captured before parsing would allow evidence that expires during those lookups to be recorded as
     * current. Each verifier receives a fresh timestamp and the resulting record is dated at completion.
     */
    PeerTrust evaluate(PeerIdentity peer, RemoteBridgeMessages.AgentHello hello, LongSupplier clock) {
        if (clock == null) {
            throw new IllegalArgumentException("clock is required");
        }
        long startedAtEpochMillis = clock.getAsLong();
        PeerEvidenceParser.ParsedEvidence parsed;
        try {
            parsed = parser.parse(peer, hello);
        } catch (RuntimeException e) {
            parsed = PeerEvidenceParser.ParsedEvidence.empty(); // a non-total parser still fails CLOSED here
        }
        boolean attestation = parsed.attestation().map(evidence -> {
            try {
                return attestationVerifier.verify(evidence, Instant.ofEpochMilli(clock.getAsLong())).isVerified();
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(false);
        boolean device = parsed.deviceKey().map(key -> {
            try {
                return deviceIdentityVerifier.verify(key, Instant.ofEpochMilli(clock.getAsLong())).isTrusted();
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(false);
        // Certificate validity is the shortest-lived transport trust dimension and is intentionally checked last,
        // after potentially slower attestation/device verification, so an expiry during verification fails closed.
        boolean cert = parsed.certRef().map(ref -> {
            try {
                return certTrustEvaluator.evaluate(ref, Instant.ofEpochMilli(clock.getAsLong())).isValid();
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(false);
        long completedAtEpochMillis = clock.getAsLong();
        if (completedAtEpochMillis < startedAtEpochMillis
                || completedAtEpochMillis - startedAtEpochMillis > freshnessTtlMillis) {
            cert = false;
            attestation = false;
            device = false;
        }
        PeerTrust trust = new PeerTrust(cert, attestation, device, peer.certBoundDeviceId(),
                hello.deviceId(), completedAtEpochMillis, hello.supportedFeatures());
        return trust;
    }

    /** Publish a previously evaluated result for this exact authenticated peer. */
    void publish(PeerIdentity peer, PeerTrust trust) {
        byPeer.put(peer.transportPeerKey(), trust);
    }

    /** The peer's trust record ONLY while fresh — stale (or absent) verification is no verification. */
    public Optional<PeerTrust> fresh(String transportPeerKey, long nowEpochMillis) {
        PeerTrust trust = byPeer.get(transportPeerKey);
        if (!isFresh(trust, nowEpochMillis)) {
            return Optional.empty();
        }
        return Optional.of(trust);
    }

    boolean isFresh(PeerTrust trust, long nowEpochMillis) {
        return trust != null && nowEpochMillis >= trust.recordedAtEpochMillis()
                && nowEpochMillis - trust.recordedAtEpochMillis() <= freshnessTtlMillis;
    }

    public void forget(String transportPeerKey) {
        byPeer.remove(transportPeerKey);
    }
}

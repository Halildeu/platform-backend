package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.AttestationVerifier;
import com.example.endpointadmin.remoteaccess.CertTrustEvaluator;
import com.example.endpointadmin.remoteaccess.DeviceIdentityVerifier;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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
                            long recordedAtEpochMillis) {
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
        PeerEvidenceParser.ParsedEvidence parsed = parser.parse(peer, hello);
        Instant now = Instant.ofEpochMilli(nowEpochMillis);
        boolean cert = parsed.certRef().map(ref -> {
            try {
                return certTrustEvaluator.evaluate(ref, now).isValid();
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(false);
        boolean attestation = parsed.attestation().map(evidence -> {
            try {
                return attestationVerifier.verify(evidence, now).isVerified();
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(false);
        boolean device = parsed.deviceKey().map(key -> {
            try {
                return deviceIdentityVerifier.verify(key, now).isTrusted();
            } catch (RuntimeException e) {
                return false;
            }
        }).orElse(false);
        PeerTrust trust = new PeerTrust(cert, attestation, device, peer.certBoundDeviceId(),
                hello.deviceId(), nowEpochMillis);
        byPeer.put(peer.transportPeerKey(), trust);
        return trust;
    }

    /** The peer's trust record ONLY while fresh — stale (or absent) verification is no verification. */
    public Optional<PeerTrust> fresh(String transportPeerKey, long nowEpochMillis) {
        PeerTrust trust = byPeer.get(transportPeerKey);
        if (trust == null || nowEpochMillis - trust.recordedAtEpochMillis() > freshnessTtlMillis
                || nowEpochMillis < trust.recordedAtEpochMillis()) {
            return Optional.empty();
        }
        return Optional.of(trust);
    }

    public void forget(String transportPeerKey) {
        byPeer.remove(transportPeerKey);
    }
}

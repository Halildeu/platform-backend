package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.DeviceKeyChallenge;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Faz 22.6 #548 slice-1 step-5 (Codex {@code 019efada}) — holds the most recent TPM-native device-key session
 * attestation a peer answered, keyed by {@code (sessionId, transportPeerKey)}, so the
 * {@link DeviceKeyAttestationRealSessionDeviceTrustVerifier} can read it at PERMIT-evaluation time (the
 * challenge-response round-trip and the operation request are separate control-plane events).
 *
 * <p><b>NOT trust, NOT a cache that confers anything.</b> A stored entry only means "this peer answered a fresh
 * broker challenge for this live session, and the response decoded into the required shape". Every authoritative
 * fact (binding-context signature, AK certify, AK&harr;EK enrollment binding, EK chain, device-key&harr;leaf
 * equality, quote freshness) is re-derived by the verifier — this store is pure transport/correlation state.
 *
 * <p><b>Session-scoped key (Codex).</b> Keying by {@code (sessionId, transportPeerKey)} — not by peer alone —
 * means a NEW session on the same reconnected peer never reads evidence captured for a PRIOR session: the prior
 * session's sessionId differs, so its entry is unreachable (and TTL-bounded besides). A single live session per
 * peer is the store invariant upstream ({@link RemoteBridgeSessionStore}), so a peer overwrites only its own
 * current-session entry.
 *
 * <p><b>TTL fail-closed, tied to the CHALLENGE expiry (not session lifetime).</b> An entry's freshness is bounded
 * by the broker nonce window the device proved possession against; a read at/after {@code expiresAtEpochMillis}
 * returns empty AND evicts the entry (it is useless to everyone once stale — no lingering evidence). The store is
 * in-memory + broker-local: a restart drops pending evidence (the correct fail-closed posture for a short-lived
 * freshness artifact — the agent simply answers the next challenge).
 *
 * <p>Thread-safe ({@link ConcurrentHashMap} + atomic {@code computeIfPresent} expired-evict).
 */
public final class TpmDeviceKeySessionEvidenceStore {

    /**
     * One captured device-key session attestation: the BROKER's consumed challenge (the source of truth for the
     * canonical binding context + the nonce the quote must bind to) paired with the decoded TPM-native evidence,
     * and the freshness window inherited from the challenge.
     */
    public record StoredEvidence(DeviceKeyChallenge consumedChallenge,
                                 TpmDeviceKeySessionAttestation attestation,
                                 long storedAtEpochMillis,
                                 long expiresAtEpochMillis) {
        public StoredEvidence {
            Objects.requireNonNull(consumedChallenge, "consumedChallenge");
            Objects.requireNonNull(attestation, "attestation");
        }
    }

    private final ConcurrentMap<String, StoredEvidence> evidenceByKey = new ConcurrentHashMap<>();

    /**
     * Record the captured evidence for {@code (sessionId, transportPeerKey)}, replacing any prior entry for the
     * SAME key (a peer answering twice for its own live session keeps only the latest). The freshness window is
     * the broker challenge's own expiry — never extended here.
     *
     * @throws IllegalArgumentException on a blank sessionId/transportPeerKey or null evidence (fail-fast caller misuse)
     */
    public void store(String sessionId, String transportPeerKey, StoredEvidence evidence) {
        if (sessionId == null || sessionId.isBlank()
                || transportPeerKey == null || transportPeerKey.isBlank()) {
            throw new IllegalArgumentException("sessionId and transportPeerKey required to store device-key evidence");
        }
        Objects.requireNonNull(evidence, "evidence");
        evidenceByKey.put(key(sessionId, transportPeerKey), evidence);
    }

    /**
     * The fresh evidence for {@code (sessionId, transportPeerKey)} at {@code nowEpochMillis}, or empty when none
     * exists or it has expired. An expired entry is EVICTED on this read (fail-closed — stale evidence never
     * lingers to be read again). A blank key is empty (never throws on the read path — the verifier stays total).
     */
    public Optional<StoredEvidence> consumeFresh(String sessionId, String transportPeerKey, long nowEpochMillis) {
        if (sessionId == null || sessionId.isBlank()
                || transportPeerKey == null || transportPeerKey.isBlank()) {
            return Optional.empty();
        }
        StoredEvidence[] fresh = new StoredEvidence[1];
        evidenceByKey.computeIfPresent(key(sessionId, transportPeerKey), (k, stored) -> {
            if (nowEpochMillis >= stored.expiresAtEpochMillis()) {
                return null; // expired → evict on any access (useless to everyone now)
            }
            fresh[0] = stored;
            return stored; // fresh → keep (the verifier may be re-evaluated within the same window)
        });
        return Optional.ofNullable(fresh[0]);
    }

    /** Drop a session's evidence (e.g. on session terminal/evict). Idempotent. */
    public void evict(String sessionId, String transportPeerKey) {
        if (sessionId == null || sessionId.isBlank()
                || transportPeerKey == null || transportPeerKey.isBlank()) {
            return;
        }
        evidenceByKey.remove(key(sessionId, transportPeerKey));
    }

    /** Stored (un-expired-until-next-access) entry count — tests / metrics only. */
    int size() {
        return evidenceByKey.size();
    }

    private static String key(String sessionId, String transportPeerKey) {
        // both are server-controlled identifiers (sessionId is a validated wire id; transportPeerKey is the
        // authenticated mTLS fingerprint) — a space is an unambiguous separator (neither contains one).
        return sessionId + " " + transportPeerKey;
    }
}

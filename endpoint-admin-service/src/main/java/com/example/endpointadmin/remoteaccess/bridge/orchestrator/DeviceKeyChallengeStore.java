package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.DeviceKeyChallenge;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Faz 22.6 #548 slice-1 step-3 (Codex {@code 019efada}) — issues broker-nonced {@code DeviceKeyChallenge}s and
 * consumes them SINGLE-USE under a TTL. This is the freshness/replay guard the <b>canonical #548</b>
 * challenge-response rests on: the device proves it CURRENTLY controls the attested key by signing a binding
 * context over a fresh broker nonce, and that nonce can be redeemed at most once.
 *
 * <p><b>Fresh per issue:</b> a 32-byte {@link SecureRandom} nonce + a 128-bit random {@code challengeId} (hex, so
 * it always satisfies the wire id allowlist), bound to the requesting {@code transportPeerKey} and stamped with an
 * explicit validity window.
 *
 * <p><b>{@link #consume} is atomic single-use:</b> a {@code challengeId} can be redeemed at most once, only by the
 * exact peer it was issued to, only before it expires. A wrong-peer lookup leaves the challenge intact for its
 * legitimate peer (no denial-of-service, no consume); an unknown / expired / already-consumed lookup returns
 * {@link Optional#empty()} — every failure mode is the same empty result (no oracle).
 *
 * <p><b>Not trust.</b> A consumed challenge only proves "this peer answered a fresh nonce we issued, in time". The
 * {@code DEVICE_KEY_ATTESTATION_REAL} verifier (step 4) still re-derives the canonical binding context from the
 * consumed challenge + the live mTLS peer and runs the TPM crypto before any device trust.
 *
 * <p>Thread-safe (a {@link ConcurrentHashMap} + atomic {@code computeIfPresent} consume). In-memory and
 * broker-local: a process restart drops pending challenges (they simply expire / must be re-issued), which is the
 * correct fail-closed posture for a short-TTL freshness nonce.
 */
public final class DeviceKeyChallengeStore {

    /** The wire {@code protocol_version} literal the canonical #548 challenge-response carries. */
    public static final String PROTOCOL_VERSION = "device-key-session-v1";
    private static final int NONCE_BYTES = 32;
    private static final int CHALLENGE_ID_BYTES = 16;

    /** A pending challenge bound to BOTH the requesting peer (in the challenge) AND its broker session. */
    private record Pending(DeviceKeyChallenge challenge, String sessionId) {
    }

    private final SecureRandom random;
    private final ConcurrentMap<String, Pending> pending = new ConcurrentHashMap<>();

    public DeviceKeyChallengeStore() {
        this(new SecureRandom());
    }

    DeviceKeyChallengeStore(SecureRandom random) {
        this.random = random;
    }

    /**
     * Issue a fresh challenge bound to {@code sessionId} + {@code transportPeerKey}, valid for {@code ttlMillis}
     * from {@code nowEpochMillis}. Returns the challenge to send to the agent over the CONTROL stream; the pending
     * state (including the owning broker {@code sessionId}) is recorded for a later single-use {@link #consume}.
     *
     * <p><b>Session-bound (Codex #548 step-5a REVISE F1):</b> the {@code sessionId} pins the challenge to ONE
     * broker session, so a late response for a closed session can never be redeemed against a NEW session that
     * the same reconnected peer later opened. The wire {@code DeviceKeyChallenge} record is unchanged (the
     * sessionId travels on the CONTROL envelope, not the payload).
     *
     * @throws IllegalArgumentException on a blank session id / peer key or non-positive TTL (fail-fast caller misuse)
     */
    public DeviceKeyChallenge issue(String sessionId, String transportPeerKey, long ttlMillis, long nowEpochMillis) {
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("sessionId required to issue a device-key challenge");
        }
        if (transportPeerKey == null || transportPeerKey.isBlank()) {
            throw new IllegalArgumentException("transportPeerKey required to issue a device-key challenge");
        }
        if (ttlMillis <= 0) {
            throw new IllegalArgumentException("device-key challenge TTL must be positive");
        }
        evictExpired(nowEpochMillis);
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        DeviceKeyChallenge challenge = new DeviceKeyChallenge(
                newChallengeId(),
                Base64.getEncoder().encodeToString(nonce),
                nowEpochMillis,
                nowEpochMillis + ttlMillis,
                transportPeerKey,
                PROTOCOL_VERSION);
        pending.put(challenge.challengeId(), new Pending(challenge, sessionId));
        return challenge;
    }

    /**
     * Atomically consume {@code challengeId} for {@code (transportPeerKey, sessionId)} at {@code nowEpochMillis}.
     * Returns the issued challenge IFF it exists, was issued to this exact peer AND this exact broker session, and
     * has not expired — then removes it so it can never be redeemed again (single-use replay guard). A wrong-peer
     * OR wrong-session lookup KEEPS the challenge for its legitimate owner (no DoS, no cross-session redeem); an
     * unknown / expired / already-consumed lookup returns {@link Optional#empty()} — every failure mode is the
     * same empty result (no oracle).
     */
    public Optional<DeviceKeyChallenge> consume(String challengeId, String transportPeerKey, String sessionId,
                                                long nowEpochMillis) {
        if (challengeId == null || challengeId.isBlank()
                || transportPeerKey == null || transportPeerKey.isBlank()
                || sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        DeviceKeyChallenge[] consumed = new DeviceKeyChallenge[1];
        pending.computeIfPresent(challengeId, (id, p) -> {
            if (nowEpochMillis >= p.challenge().expiresAtEpochMillis()) {
                return null; // expired → remove on any access (it is useless to the legitimate owner too)
            }
            if (!p.challenge().transportPeerKey().equals(transportPeerKey) || !p.sessionId().equals(sessionId)) {
                return p; // wrong peer OR wrong session on a LIVE challenge → keep it for its owner (no DoS)
            }
            consumed[0] = p.challenge(); // valid → capture and remove (single-use)
            return null;
        });
        return Optional.ofNullable(consumed[0]);
    }

    /**
     * Evict every pending challenge for {@code (sessionId, transportPeerKey)} (Codex #548 step-5b REVISE F1): a
     * broker {@code sessionId} is client-supplied and can be reused after the prior session goes terminal, so on
     * session terminal — and defensively at a fresh {@code openSession} for the same id — the prior session's
     * pending challenge MUST be cleared, or a late response for it could be redeemed into the new same-id session.
     * Idempotent; a blank input is a no-op.
     */
    public void evictSession(String sessionId, String transportPeerKey) {
        if (sessionId == null || sessionId.isBlank()
                || transportPeerKey == null || transportPeerKey.isBlank()) {
            return;
        }
        pending.values().removeIf(p -> p.sessionId().equals(sessionId)
                && p.challenge().transportPeerKey().equals(transportPeerKey));
    }

    /** Pending (un-consumed, possibly-expired-until-next-issue) challenge count — tests / metrics only. */
    int pendingCount() {
        return pending.size();
    }

    private String newChallengeId() {
        byte[] id = new byte[CHALLENGE_ID_BYTES];
        random.nextBytes(id);
        return HexFormat.of().formatHex(id); // hex → always satisfies WireContract.isValidId
    }

    private void evictExpired(long nowEpochMillis) {
        pending.values().removeIf(p -> nowEpochMillis >= p.challenge().expiresAtEpochMillis());
    }
}

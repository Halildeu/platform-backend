package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.DeviceKeyChallenge;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.TpmDeviceKeySessionEvidenceStore.StoredEvidence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.6 #548 slice-1 step-5 — {@link TpmDeviceKeySessionEvidenceStore}: session-scoped key, challenge-expiry
 * TTL fail-closed (expired read returns empty AND evicts), and no cross-session/cross-peer bleed.
 */
class TpmDeviceKeySessionEvidenceStoreTest {

    private static final long NOW = 1_000_000L;
    private static final String SESSION = "sess-1";
    private static final String PEER = "ab".repeat(32);

    private final TpmDeviceKeySessionEvidenceStore store = new TpmDeviceKeySessionEvidenceStore();

    @Test
    void storeThenConsumeFresh_returnsEvidence() {
        store.store(SESSION, PEER, evidence(NOW + 60_000));
        Optional<StoredEvidence> got = store.consumeFresh(SESSION, PEER, NOW);
        assertThat(got).isPresent();
        assertThat(got.get().expiresAtEpochMillis()).isEqualTo(NOW + 60_000);
    }

    @Test
    void expired_returnsEmptyAndEvicts() {
        store.store(SESSION, PEER, evidence(NOW + 10));
        assertThat(store.consumeFresh(SESSION, PEER, NOW + 10)).as("at expiry → fail-closed").isEmpty();
        assertThat(store.size()).as("expired entry evicted on read").isZero();
    }

    @Test
    void otherSessionSamePeer_doesNotBleed() {
        store.store(SESSION, PEER, evidence(NOW + 60_000));
        assertThat(store.consumeFresh("sess-2", PEER, NOW)).isEmpty();
    }

    @Test
    void otherPeerSameSession_doesNotBleed() {
        store.store(SESSION, PEER, evidence(NOW + 60_000));
        assertThat(store.consumeFresh(SESSION, "cd".repeat(32), NOW)).isEmpty();
    }

    @Test
    void evict_removesEntry() {
        store.store(SESSION, PEER, evidence(NOW + 60_000));
        store.evict(SESSION, PEER);
        assertThat(store.consumeFresh(SESSION, PEER, NOW)).isEmpty();
    }

    @Test
    void latestStoreWins_forSameKey() {
        store.store(SESSION, PEER, evidence(NOW + 10_000));
        store.store(SESSION, PEER, evidence(NOW + 99_000));
        assertThat(store.consumeFresh(SESSION, PEER, NOW).orElseThrow().expiresAtEpochMillis())
                .isEqualTo(NOW + 99_000);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void blankKey_storeRejected_readEmpty() {
        assertThatThrownBy(() -> store.store(" ", PEER, evidence(NOW + 1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(store.consumeFresh(null, PEER, NOW)).isEmpty();
        assertThat(store.consumeFresh(SESSION, " ", NOW)).isEmpty();
    }

    private static StoredEvidence evidence(long expiresAt) {
        DeviceKeyChallenge challenge = new DeviceKeyChallenge("cid", "bm9uY2U=", NOW, expiresAt, PEER,
                "device-key-session-v1");
        TpmDeviceKeySessionAttestation attestation = new TpmDeviceKeySessionAttestation("cid",
                "device-key-session-v1", new byte[]{1}, new byte[]{2}, new byte[]{3}, new byte[0], new byte[0],
                List.of(), new byte[]{4}, new byte[]{5}, new byte[]{6}, new byte[]{7}, new byte[]{8}, new byte[]{9},
                NOW);
        return new StoredEvidence(challenge, attestation, NOW, expiresAt);
    }
}

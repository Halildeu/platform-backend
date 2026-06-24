package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.DeviceKeyChallenge;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;
import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 #548 slice-1 step-3 — {@link DeviceKeyChallengeStore} freshness/replay guard: fresh broker-nonced
 * issue + atomic single-use, peer-bound, TTL-bounded consume (fail-closed, no oracle).
 */
class DeviceKeyChallengeStoreTest {

    private static final String PEER = "peer-leaf-thumbprint-abc";
    private static final String OTHER_PEER = "peer-leaf-thumbprint-xyz";
    private static final long NOW = 1_000L;
    private static final long TTL = 60_000L;

    @Test
    void issueThenConsumeRedeemsExactlyOnce() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge issued = store.issue(PEER, TTL, NOW);
        assertEquals(1, store.pendingCount());

        Optional<DeviceKeyChallenge> consumed = store.consume(issued.challengeId(), PEER, NOW + 1_000L);
        assertTrue(consumed.isPresent(), "a fresh in-window challenge consumes for its peer");
        assertEquals(issued.challengeId(), consumed.get().challengeId());
        assertEquals(0, store.pendingCount(), "consume removes the pending challenge");
    }

    @Test
    void issuedChallengeHasAFreshNonceAndAWireValidId() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge c = store.issue(PEER, TTL, NOW);
        assertTrue(WireContract.isValidId(c.challengeId()), "challengeId must satisfy the wire id allowlist");
        assertEquals(32, Base64.getDecoder().decode(c.nonceB64()).length, "nonce is 32 fresh random bytes");
        assertEquals(NOW, c.issuedAtEpochMillis());
        assertEquals(NOW + TTL, c.expiresAtEpochMillis());
        assertEquals(PEER, c.transportPeerKey());
        assertEquals(DeviceKeyChallengeStore.PROTOCOL_VERSION, c.protocolVersion());
    }

    @Test
    void twoIssuesAreIndependentlyFresh() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge a = store.issue(PEER, TTL, NOW);
        DeviceKeyChallenge b = store.issue(PEER, TTL, NOW);
        assertNotEquals(a.challengeId(), b.challengeId(), "challengeIds are unique per issue");
        assertNotEquals(a.nonceB64(), b.nonceB64(), "nonces are fresh per issue");
        assertEquals(2, store.pendingCount());
    }

    @Test
    void consumeUnknownChallengeIdIsEmpty() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        assertTrue(store.consume("deadbeef", PEER, NOW).isEmpty());
    }

    @Test
    void consumeAfterExpiryIsEmptyAndEvicts() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge issued = store.issue(PEER, TTL, NOW);
        Optional<DeviceKeyChallenge> consumed = store.consume(issued.challengeId(), PEER, NOW + TTL);
        assertTrue(consumed.isEmpty(), "at expiry (now >= expiresAt) the challenge is no longer redeemable");
        assertEquals(0, store.pendingCount(), "an expired challenge is removed on the failed consume");
    }

    @Test
    void doubleConsumeIsRejectedAsReplay() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge issued = store.issue(PEER, TTL, NOW);
        assertTrue(store.consume(issued.challengeId(), PEER, NOW).isPresent(), "first consume succeeds");
        assertTrue(store.consume(issued.challengeId(), PEER, NOW).isEmpty(), "a replayed challengeId is rejected");
    }

    @Test
    void wrongPeerCannotConsumeAndLeavesItForTheLegitimatePeer() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge issued = store.issue(PEER, TTL, NOW);
        assertTrue(store.consume(issued.challengeId(), OTHER_PEER, NOW).isEmpty(),
                "a different peer cannot consume the challenge");
        assertEquals(1, store.pendingCount(), "a wrong-peer attempt must not evict the challenge (no DoS)");
        assertTrue(store.consume(issued.challengeId(), PEER, NOW).isPresent(),
                "the legitimate peer can still consume it");
    }

    @Test
    void anExpiredChallengeIsEvictedOnAnyAccessEvenAWrongPeerProbe() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge issued = store.issue(PEER, TTL, NOW);
        // a wrong peer probing it AFTER expiry: still empty, and the dead entry is cleaned up
        assertTrue(store.consume(issued.challengeId(), OTHER_PEER, NOW + TTL).isEmpty());
        assertEquals(0, store.pendingCount(), "an expired challenge is removed even on a wrong-peer access");
    }

    @Test
    void issueRejectsBlankPeerOrNonPositiveTtl() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        assertThrows(IllegalArgumentException.class, () -> store.issue("  ", TTL, NOW));
        assertThrows(IllegalArgumentException.class, () -> store.issue(PEER, 0L, NOW));
        assertThrows(IllegalArgumentException.class, () -> store.issue(PEER, -1L, NOW));
    }

    @Test
    void consumeWithBlankInputsIsEmpty() {
        DeviceKeyChallengeStore store = new DeviceKeyChallengeStore();
        DeviceKeyChallenge issued = store.issue(PEER, TTL, NOW);
        assertTrue(store.consume("", PEER, NOW).isEmpty());
        assertTrue(store.consume(issued.challengeId(), "  ", NOW).isEmpty());
        assertFalse(store.consume(issued.challengeId(), PEER, NOW).isEmpty(), "valid inputs still consume");
    }
}

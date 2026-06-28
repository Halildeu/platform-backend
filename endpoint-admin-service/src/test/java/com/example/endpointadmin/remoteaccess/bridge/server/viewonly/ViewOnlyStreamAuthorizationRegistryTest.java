package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry.Authorization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 — the fail-closed VIEW_ONLY fanout authorization gate (Codex 019f078a). */
class ViewOnlyStreamAuthorizationRegistryTest {

    private static Authorization auth(String session, String stream, String peer, long expiry) {
        return new Authorization(session, stream, peer, "operator@acik", "device-1", expiry);
    }

    @Test
    void authorizedStream_passesForBoundPeerBeforeExpiry() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.authorize(auth("s1", "op-1", "peer-A", 1_000));

        assertTrue(registry.isAuthorized("s1", "op-1", "peer-A", 500));
    }

    @Test
    void wrongPeer_failsClosed() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.authorize(auth("s1", "op-1", "peer-A", 1_000));

        assertFalse(registry.isAuthorized("s1", "op-1", "peer-B", 500)); // another agent cannot ride the grant
    }

    @Test
    void expiredAuthorization_failsClosed() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.authorize(auth("s1", "op-1", "peer-A", 1_000));

        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 1_000)); // now == expiry → expired
        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 5_000));
    }

    @Test
    void unknownStream_failsClosed() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 0));
        assertTrue(registry.lookup("s1", "op-1").isEmpty());
    }

    @Test
    void revokeStream_removesOnlyThatStream() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.authorize(auth("s1", "op-1", "peer-A", 1_000));
        registry.authorize(auth("s1", "op-2", "peer-A", 1_000));

        registry.revokeStream("s1", "op-1");

        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 500));
        assertTrue(registry.isAuthorized("s1", "op-2", "peer-A", 500));
    }

    @Test
    void revokeSession_removesAllStreams_withoutPrefixCollision() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.authorize(auth("s1", "op-1", "peer-A", 1_000));
        registry.authorize(auth("s1", "op-2", "peer-A", 1_000));
        registry.authorize(auth("s10", "op-9", "peer-A", 1_000)); // a session whose id has s1 as a prefix

        registry.revokeSession("s1");

        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 500));
        assertFalse(registry.isAuthorized("s1", "op-2", "peer-A", 500));
        assertTrue(registry.isAuthorized("s10", "op-9", "peer-A", 500)); // s10 is NOT revoked by revoking s1
    }

    @Test
    void purgeExpired_dropsOnlyExpired() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.authorize(auth("s1", "op-1", "peer-A", 1_000)); // expires at 1000
        registry.authorize(auth("s1", "op-2", "peer-A", 5_000)); // expires at 5000

        assertEquals(1, registry.purgeExpired(2_000));
        assertTrue(registry.lookup("s1", "op-1").isEmpty());
        assertTrue(registry.lookup("s1", "op-2").isPresent());
    }

    @Test
    void authorization_requiresIdsAndPeer() {
        assertThrows(IllegalArgumentException.class, () -> auth("", "op-1", "peer-A", 1));
        assertThrows(IllegalArgumentException.class, () -> auth("s1", " ", "peer-A", 1));
        assertThrows(IllegalArgumentException.class, () -> auth("s1", "op-1", "", 1));
    }

    @Test
    void nullArgs_areNotAuthorized() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        assertFalse(registry.isAuthorized(null, "op-1", "peer-A", 0));
        assertFalse(registry.isAuthorized("s1", null, "peer-A", 0));
        assertFalse(registry.isAuthorized("s1", "op-1", null, 0));
    }

    @Test
    void authorize_returnsTrueWhenAccepted() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        assertTrue(registry.authorize(auth("s1", "op-1", "peer-A", 1_000)));
        assertTrue(registry.isAuthorized("s1", "op-1", "peer-A", 0));
    }

    @Test
    void authorizeAfterRevokeSession_isRefused_terminateWins() {
        // models the operator push→authorize gap racing an agent terminate: the terminate ran first (tombstone),
        // so a late authorize for the same incarnation is refused and records nothing (fail-closed).
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.revokeSession("s1");

        assertFalse(registry.authorize(auth("s1", "op-1", "peer-A", 1_000)));
        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 0));
        assertTrue(registry.lookup("s1", "op-1").isEmpty());
    }

    @Test
    void beginSession_clearsTombstoneAndStaleAuthz() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.authorize(auth("s1", "op-old", "peer-A", 1_000)); // a prior incarnation's grant
        registry.revokeSession("s1");                              // terminal → tombstone + cleared

        registry.beginSession("s1"); // a fresh incarnation reuses the sessionId

        assertTrue(registry.lookup("s1", "op-old").isEmpty());     // stale grant gone
        assertTrue(registry.authorize(auth("s1", "op-2", "peer-A", 1_000))); // reuse can authorize again
        assertTrue(registry.isAuthorized("s1", "op-2", "peer-A", 0));
    }

    @Test
    void revokeSession_tombstone_doesNotBlockADifferentSession() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.revokeSession("s1");
        assertTrue(registry.authorize(auth("s2", "op-1", "peer-A", 1_000))); // different session unaffected
        assertTrue(registry.isAuthorized("s2", "op-1", "peer-A", 0));
    }
}

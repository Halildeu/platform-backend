package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry.Authorization;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 — the fail-closed, incarnation-bound VIEW_ONLY fanout authorization gate (Codex 019f078a + 019f0e78). */
class ViewOnlyStreamAuthorizationRegistryTest {

    // an opaque incarnation token (the broker session object identity in production)
    private static final Object INC = new Object();

    private static Authorization auth(String session, String stream, String peer, long expiry) {
        return new Authorization(session, stream, peer, "operator@acik", "device-1", expiry);
    }

    @Test
    void authorizedStream_passesForBoundPeerBeforeExpiry() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.beginSession("s1", INC);
        assertTrue(registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000)));

        assertTrue(registry.isAuthorized("s1", "op-1", "peer-A", 500));
    }

    @Test
    void wrongPeer_failsClosed() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.beginSession("s1", INC);
        registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000));

        assertFalse(registry.isAuthorized("s1", "op-1", "peer-B", 500)); // another agent cannot ride the grant
    }

    @Test
    void expiredAuthorization_failsClosed() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.beginSession("s1", INC);
        registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000));

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
    void authorizeWithoutBegin_isRefused() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        // no beginSession → no current incarnation → fail-closed
        assertFalse(registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000)));
        assertTrue(registry.lookup("s1", "op-1").isEmpty());
    }

    @Test
    void revokeStream_removesOnlyThatStream() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.beginSession("s1", INC);
        registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000));
        registry.authorize(INC, auth("s1", "op-2", "peer-A", 1_000));

        registry.revokeStream("s1", "op-1");

        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 500));
        assertTrue(registry.isAuthorized("s1", "op-2", "peer-A", 500));
    }

    @Test
    void revokeSession_removesAllStreams_withoutPrefixCollision() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        Object inc1 = new Object();
        Object inc10 = new Object();
        registry.beginSession("s1", inc1);
        registry.authorize(inc1, auth("s1", "op-1", "peer-A", 1_000));
        registry.authorize(inc1, auth("s1", "op-2", "peer-A", 1_000));
        registry.beginSession("s10", inc10); // a session whose id has s1 as a prefix
        registry.authorize(inc10, auth("s10", "op-9", "peer-A", 1_000));

        registry.revokeSession("s1");

        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 500));
        assertFalse(registry.isAuthorized("s1", "op-2", "peer-A", 500));
        assertTrue(registry.isAuthorized("s10", "op-9", "peer-A", 500)); // s10 is NOT revoked by revoking s1
    }

    @Test
    void purgeExpired_dropsOnlyExpired() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.beginSession("s1", INC);
        registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000)); // expires at 1000
        registry.authorize(INC, auth("s1", "op-2", "peer-A", 5_000)); // expires at 5000

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
        registry.beginSession("s1", INC);
        assertTrue(registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000)));
        assertTrue(registry.isAuthorized("s1", "op-1", "peer-A", 0));
    }

    @Test
    void authorizeAfterRevokeSession_isRefused_terminateWins() {
        // models the operator push→authorize gap racing a SAME-incarnation agent terminate: the terminate ran
        // first (TERMINATED marker), so a late authorize is refused and records nothing (fail-closed).
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.beginSession("s1", INC);
        registry.revokeSession("s1");

        assertFalse(registry.authorize(INC, auth("s1", "op-1", "peer-A", 1_000)));
        assertFalse(registry.isAuthorized("s1", "op-1", "peer-A", 0));
        assertTrue(registry.lookup("s1", "op-1").isEmpty());
    }

    @Test
    void staleIncarnationAuthorizeAfterReopen_isRefused() {
        // the cross-incarnation race: old incarnation's delayed operator thread authorizes AFTER the id was
        // terminated and reopened as a new incarnation. The stale token is no longer current → refused.
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        Object oldIncarnation = new Object();
        Object newIncarnation = new Object();
        registry.beginSession("s1", oldIncarnation);
        registry.revokeSession("s1");           // old terminal
        registry.beginSession("s1", newIncarnation); // id reused by a fresh incarnation

        // the old operator thread finally runs with the OLD token → refused (not the current incarnation)
        assertFalse(registry.authorize(oldIncarnation, auth("s1", "op-old", "peer-A", 1_000)));
        assertTrue(registry.lookup("s1", "op-old").isEmpty());
        // the new incarnation can authorize normally
        assertTrue(registry.authorize(newIncarnation, auth("s1", "op-new", "peer-A", 1_000)));
        assertTrue(registry.isAuthorized("s1", "op-new", "peer-A", 0));
    }

    @Test
    void beginSession_clearsStaleAuthzForAReusedId() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        Object inc1 = new Object();
        Object inc2 = new Object();
        registry.beginSession("s1", inc1);
        registry.authorize(inc1, auth("s1", "op-old", "peer-A", 1_000)); // a prior incarnation's grant
        registry.revokeSession("s1");

        registry.beginSession("s1", inc2); // a fresh incarnation reuses the sessionId

        assertTrue(registry.lookup("s1", "op-old").isEmpty()); // stale grant gone
        assertTrue(registry.authorize(inc2, auth("s1", "op-2", "peer-A", 1_000)));
        assertTrue(registry.isAuthorized("s1", "op-2", "peer-A", 0));
    }

    @Test
    void revokeSession_doesNotBlockADifferentSession() {
        ViewOnlyStreamAuthorizationRegistry registry = new ViewOnlyStreamAuthorizationRegistry();
        registry.revokeSession("s1");
        registry.beginSession("s2", INC);
        assertTrue(registry.authorize(INC, auth("s2", "op-1", "peer-A", 1_000))); // different session unaffected
        assertTrue(registry.isAuthorized("s2", "op-1", "peer-A", 0));
    }
}

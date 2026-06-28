package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.example.endpointadmin.remoteaccess.bridge.server.viewonly.ViewOnlyStreamAuthorizationRegistry.Authorization;
import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 (Codex 019f0e78) — the shared terminal-cleanup seam: revoke authz + close viewers, atomically. */
class ViewOnlySessionLifecycleTest {

    private static ViewOnlyFrame frame() {
        return new ViewOnlyFrame("s1", "op-1", 0, "image/png", ByteString.copyFromUtf8("px"), false, 0);
    }

    @Test
    void terminate_revokesAuthorizationAndClosesViewers() {
        ViewOnlyStreamAuthorizationRegistry authz = new ViewOnlyStreamAuthorizationRegistry();
        ViewOnlyViewerRegistry viewers = new ViewOnlyViewerRegistry(1);
        ViewOnlySessionLifecycle lifecycle = new ViewOnlySessionLifecycle(authz, viewers);

        Object inc = new Object();
        lifecycle.beginSession("s1", inc);
        assertTrue(lifecycle.authorizeStream(inc,
                new Authorization("s1", "op-1", "peer-A", "operator@x", "dev-1", 10_000)));
        ViewOnlyViewerSubscription viewer = viewers.subscribe("s1", null).orElseThrow();
        assertEquals(1, viewers.publish(frame())); // fanout works while live
        assertTrue(authz.isAuthorized("s1", "op-1", "peer-A", 0));

        lifecycle.terminate("s1");

        assertFalse(authz.isAuthorized("s1", "op-1", "peer-A", 0)); // authorization gone → fail-closed
        assertTrue(viewer.isClosed());                              // viewer detached
        assertEquals(0, viewers.viewerCount("s1"));
        assertEquals(0, viewers.publish(frame()));                  // nothing fanned out post-terminate
    }

    @Test
    void terminate_isIdempotentAndNullSafe() {
        ViewOnlyStreamAuthorizationRegistry authz = new ViewOnlyStreamAuthorizationRegistry();
        ViewOnlyViewerRegistry viewers = new ViewOnlyViewerRegistry(1);
        ViewOnlySessionLifecycle lifecycle = new ViewOnlySessionLifecycle(authz, viewers);

        lifecycle.terminate("never-existed"); // no throw on unknown session
        lifecycle.terminate(null);            // no throw on null
        lifecycle.terminate("never-existed"); // idempotent
    }

    @Test
    void terminate_onlyAffectsTheNamedSession() {
        ViewOnlyStreamAuthorizationRegistry authz = new ViewOnlyStreamAuthorizationRegistry();
        ViewOnlyViewerRegistry viewers = new ViewOnlyViewerRegistry(1);
        ViewOnlySessionLifecycle lifecycle = new ViewOnlySessionLifecycle(authz, viewers);

        Object incA = new Object();
        Object incB = new Object();
        lifecycle.beginSession("sA", incA);
        lifecycle.beginSession("sB", incB);
        lifecycle.authorizeStream(incA, new Authorization("sA", "op-1", "peer-A", "operator@x", "dev-1", 10_000));
        lifecycle.authorizeStream(incB, new Authorization("sB", "op-2", "peer-B", "operator@x", "dev-2", 10_000));
        ViewOnlyViewerSubscription bViewer = viewers.subscribe("sB", null).orElseThrow();

        lifecycle.terminate("sA");

        assertFalse(authz.isAuthorized("sA", "op-1", "peer-A", 0));
        assertTrue(authz.isAuthorized("sB", "op-2", "peer-B", 0)); // session B untouched
        assertFalse(bViewer.isClosed());
    }

    @Test
    void requiresBothRegistries() {
        assertThrows(NullPointerException.class,
                () -> new ViewOnlySessionLifecycle(null, new ViewOnlyViewerRegistry(1)));
        assertThrows(NullPointerException.class,
                () -> new ViewOnlySessionLifecycle(new ViewOnlyStreamAuthorizationRegistry(), null));
    }
}

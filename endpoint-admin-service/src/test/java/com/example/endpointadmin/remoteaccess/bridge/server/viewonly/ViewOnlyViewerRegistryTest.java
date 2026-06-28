package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 — bounded, latest-wins, per-session, no-persistence fanout seam (Codex 019f078a). */
class ViewOnlyViewerRegistryTest {

    private static ViewOnlyFrame frame(String sessionId, long seq) {
        return new ViewOnlyFrame(sessionId, "stream-1", seq, "image/png",
                ByteString.copyFromUtf8("f" + seq), false, seq);
    }

    @Test
    void publishToSubscribedViewer_deliversLatest() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = registry.subscribe("s1", null).orElseThrow();

        assertEquals(1, registry.publish(frame("s1", 0)));
        assertEquals(1, registry.publish(frame("s1", 1)));

        Optional<ViewOnlyFrame> got = sub.poll();
        assertTrue(got.isPresent());
        assertEquals(1, got.get().frameSeq()); // latest-wins
    }

    @Test
    void noViewer_dropsFrame() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        assertEquals(0, registry.publish(frame("s1", 0))); // no subscriber → dropped, returns 0
    }

    @Test
    void maxViewersPerSession_isEnforced() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        assertTrue(registry.subscribe("s1", null).isPresent());
        assertTrue(registry.subscribe("s1", null).isEmpty()); // bound reached → empty
        assertEquals(1, registry.viewerCount("s1"));
    }

    @Test
    void higherBound_allowsMoreThenStops() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(2);
        assertTrue(registry.subscribe("s1", null).isPresent());
        assertTrue(registry.subscribe("s1", null).isPresent());
        assertTrue(registry.subscribe("s1", null).isEmpty());
        assertEquals(2, registry.viewerCount("s1"));
        assertEquals(2, registry.publish(frame("s1", 0)));
    }

    @Test
    void perSessionIsolation() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription a = registry.subscribe("sA", null).orElseThrow();
        ViewOnlyViewerSubscription b = registry.subscribe("sB", null).orElseThrow();

        assertEquals(1, registry.publish(frame("sA", 0)));
        assertTrue(a.poll().isPresent());
        assertTrue(b.poll().isEmpty()); // session B viewer never saw session A's frame
    }

    @Test
    void unsubscribe_freesTheSlotAndStopsDelivery() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = registry.subscribe("s1", null).orElseThrow();
        registry.unsubscribe(sub);

        assertTrue(sub.isClosed());
        assertEquals(0, registry.viewerCount("s1"));
        assertEquals(0, registry.publish(frame("s1", 0)));
        assertTrue(registry.subscribe("s1", null).isPresent()); // slot freed → a new viewer can attach
    }

    @Test
    void closeSession_detachesEveryViewer() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(2);
        ViewOnlyViewerSubscription a = registry.subscribe("s1", null).orElseThrow();
        ViewOnlyViewerSubscription b = registry.subscribe("s1", null).orElseThrow();

        registry.closeSession("s1");

        assertTrue(a.isClosed());
        assertTrue(b.isClosed());
        assertEquals(0, registry.viewerCount("s1"));
        assertEquals(0, registry.publish(frame("s1", 0)));
    }

    @Test
    void defaultBound_isOneEvenForNonPositiveConfig() {
        assertEquals(1, new ViewOnlyViewerRegistry(0).maxViewersPerSession());
        assertEquals(1, new ViewOnlyViewerRegistry(-5).maxViewersPerSession());
    }

    @Test
    void closedSubscriptionSlot_isReclaimedOnNextSubscribe() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = registry.subscribe("s1", null).orElseThrow();
        sub.close(); // closed but not unsubscribed
        // the next subscribe prunes the closed entry and succeeds (no permanent slot leak)
        assertFalse(registry.subscribe("s1", null).isEmpty());
    }
}

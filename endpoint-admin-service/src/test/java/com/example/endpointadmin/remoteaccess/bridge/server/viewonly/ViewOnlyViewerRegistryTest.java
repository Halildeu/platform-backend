package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Faz 22.6 #1580 — bounded, latest-wins, per-session, no-persistence fanout seam (Codex 019f078a). */
class ViewOnlyViewerRegistryTest {

    private static Optional<ViewOnlyViewerSubscription> subscribe(
            ViewOnlyViewerRegistry registry, String sessionId, Runnable frameAvailable) {
        Optional<ViewOnlyViewerSubscription> reserved = registry.reserve(
                sessionId, "stream-1", "tenant-1", "operator-1", frameAvailable);
        reserved.ifPresent(subscription -> assertTrue(registry.activate(subscription)));
        return reserved;
    }

    private static ViewOnlyFrame frame(String sessionId, long seq) {
        return new ViewOnlyFrame(sessionId, "stream-1", seq, "image/png",
                ByteString.copyFromUtf8("f" + seq), false, seq);
    }

    @Test
    void publishToSubscribedViewer_deliversLatest() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = subscribe(registry, "s1", null).orElseThrow();

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
    void reservedViewerRejectsFramesUntilActivated() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = registry.reserve(
                "s1", "stream-1", "tenant-1", "operator-1", null).orElseThrow();

        assertEquals(0, registry.publish(frame("s1", 0)));
        assertTrue(sub.poll().isEmpty());
        assertTrue(registry.activate(sub));
        assertEquals(1, registry.publish(frame("s1", 1)));
        assertEquals(1L, sub.poll().orElseThrow().frameSeq());
    }

    @Test
    void closedReservationCannotBeActivated() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = registry.reserve(
                "s1", "stream-1", "tenant-1", "operator-1", null).orElseThrow();

        registry.unsubscribe(sub);

        assertFalse(registry.activate(sub));
        assertEquals(0, registry.publish(frame("s1", 0)));
    }

    @Test
    void maxViewersPerSession_isEnforced() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        assertTrue(subscribe(registry, "s1", null).isPresent());
        assertTrue(subscribe(registry, "s1", null).isEmpty()); // bound reached → empty
        assertEquals(1, registry.viewerCount("s1"));
    }

    @Test
    void higherBound_allowsMoreThenStops() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(2);
        assertTrue(subscribe(registry, "s1", null).isPresent());
        assertTrue(subscribe(registry, "s1", null).isPresent());
        assertTrue(subscribe(registry, "s1", null).isEmpty());
        assertEquals(2, registry.viewerCount("s1"));
        assertEquals(2, registry.publish(frame("s1", 0)));
    }

    @Test
    void perSessionIsolation() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription a = subscribe(registry, "sA", null).orElseThrow();
        ViewOnlyViewerSubscription b = subscribe(registry, "sB", null).orElseThrow();

        assertEquals(1, registry.publish(frame("sA", 0)));
        assertTrue(a.poll().isPresent());
        assertTrue(b.poll().isEmpty()); // session B viewer never saw session A's frame
    }

    @Test
    void unsubscribe_freesTheSlotAndStopsDelivery() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = subscribe(registry, "s1", null).orElseThrow();
        registry.unsubscribe(sub);

        assertTrue(sub.isClosed());
        assertEquals(0, registry.viewerCount("s1"));
        assertEquals(0, registry.publish(frame("s1", 0)));
        assertTrue(subscribe(registry, "s1", null).isPresent()); // slot freed → a new viewer can attach
    }

    @Test
    void closeSession_detachesEveryViewer() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(2);
        ViewOnlyViewerSubscription a = subscribe(registry, "s1", null).orElseThrow();
        ViewOnlyViewerSubscription b = subscribe(registry, "s1", null).orElseThrow();

        registry.closeSession("s1");

        assertTrue(a.isClosed());
        assertTrue(b.isClosed());
        assertEquals(0, registry.viewerCount("s1"));
        assertEquals(0, registry.publish(frame("s1", 0)));
    }

    @Test
    void closeSessionWaitsForAcceptedFrameMetadataBarrier() throws Exception {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = subscribe(registry, "s1", null).orElseThrow();
        CountDownLatch metadataEntered = new CountDownLatch(1);
        CountDownLatch releaseMetadata = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> publish = executor.submit(() -> registry.publish(frame("s1", 1), () -> {
                metadataEntered.countDown();
                try {
                    if (!releaseMetadata.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test metadata release timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("test metadata interrupted", interrupted);
                }
            }));
            assertTrue(metadataEntered.await(5, TimeUnit.SECONDS));

            Future<?> close = executor.submit(() -> registry.closeSession("s1"));
            assertThrows(TimeoutException.class, () -> close.get(150, TimeUnit.MILLISECONDS));

            releaseMetadata.countDown();
            assertEquals(1, publish.get(5, TimeUnit.SECONDS));
            close.get(5, TimeUnit.SECONDS);
            assertTrue(sub.isClosed());
        } finally {
            releaseMetadata.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void defaultBound_isOneEvenForNonPositiveConfig() {
        assertEquals(1, new ViewOnlyViewerRegistry(0).maxViewersPerSession());
        assertEquals(1, new ViewOnlyViewerRegistry(-5).maxViewersPerSession());
    }

    @Test
    void closedSubscriptionSlot_isReclaimedOnNextSubscribe() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = subscribe(registry, "s1", null).orElseThrow();
        sub.close(); // closed but not unsubscribed
        // the next subscribe prunes the closed entry and succeeds (no permanent slot leak)
        assertFalse(subscribe(registry, "s1", null).isEmpty());
    }

    @Test
    void renderAcknowledgementIsBoundToSessionStreamViewerAndSentSequence() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = subscribe(registry, "s1", null).orElseThrow();
        ViewOnlyFrame sent = frame("s1", 9L);
        assertTrue(sub.markSent(sent, 12L));

        assertTrue(registry.acknowledgeRendered("s1", "wrong-stream", "tenant-1", "operator-1",
                sub.viewerId(), 9L, 20L).isEmpty());
        assertTrue(registry.acknowledgeRendered("wrong-session", "stream-1", "tenant-1", "operator-1",
                sub.viewerId(), 9L, 20L).isEmpty());
        assertTrue(registry.acknowledgeRendered("s1", "stream-1", "wrong-tenant", "operator-1",
                sub.viewerId(), 9L, 20L).isEmpty());
        assertTrue(registry.acknowledgeRendered("s1", "stream-1", "tenant-1", "wrong-operator",
                sub.viewerId(), 9L, 20L).isEmpty());
        assertTrue(registry.acknowledgeRendered("s1", "stream-1", "tenant-1", "operator-1",
                "wrong-viewer", 9L, 20L).isEmpty());
        assertTrue(registry.acknowledgeRendered("s1", "stream-1", "tenant-1", "operator-1",
                sub.viewerId(), 8L, 20L).isEmpty());

        assertEquals(11L, registry.acknowledgeRendered(
                "s1", "stream-1", "tenant-1", "operator-1",
                sub.viewerId(), 9L, 20L).orElseThrow().endToEndAgeMillis());
        assertTrue(registry.acknowledgeRendered("s1", "stream-1", "tenant-1", "operator-1",
                sub.viewerId(), 9L, 21L).isEmpty());
    }

    @Test
    void publishNeverCrossesTheSubscribedStreamBoundary() {
        ViewOnlyViewerRegistry registry = new ViewOnlyViewerRegistry(1);
        ViewOnlyViewerSubscription sub = subscribe(registry, "s1", null).orElseThrow();
        ViewOnlyFrame otherStream = new ViewOnlyFrame(
                "s1", "stream-2", 1L, "image/png", ByteString.copyFromUtf8("other"), false, 1L);

        assertEquals(0, registry.publish(otherStream));
        assertTrue(sub.poll().isEmpty());
        assertEquals(1, registry.publish(frame("s1", 2L)));
        assertEquals(2L, sub.poll().orElseThrow().frameSeq());
    }
}

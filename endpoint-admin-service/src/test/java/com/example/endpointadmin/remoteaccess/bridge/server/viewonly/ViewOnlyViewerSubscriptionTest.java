package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 — the single-slot latest-wins viewer mailbox (Codex 019f078a). */
class ViewOnlyViewerSubscriptionTest {

    private static ViewOnlyViewerSubscription subscription(String sessionId, String streamId,
                                                            String viewerId, Runnable frameAvailable) {
        return new ViewOnlyViewerSubscription(
                sessionId, streamId, "tenant-1", "operator-1", viewerId, frameAvailable);
    }

    private static ViewOnlyFrame frame(long seq) {
        return new ViewOnlyFrame("s1", "stream-1", seq, "image/png",
                ByteString.copyFromUtf8("f" + seq), false, seq);
    }

    @Test
    void latestWins_overwritesUnconsumedFrame() {
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
        assertTrue(sub.offer(frame(0)));
        assertTrue(sub.offer(frame(1))); // overwrites the un-consumed frame 0 (dropped, not buffered)
        Optional<ViewOnlyFrame> polled = sub.poll();
        assertTrue(polled.isPresent());
        assertEquals(1, polled.get().frameSeq());
        assertTrue(sub.poll().isEmpty()); // slot cleared after poll
    }

    @Test
    void frameAvailableListener_isInvoked_andAThrowIsSwallowed() {
        AtomicInteger calls = new AtomicInteger();
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", () -> {
            calls.incrementAndGet();
            throw new RuntimeException("viewer listener fault must not break the DATA stream");
        });
        assertTrue(sub.offer(frame(0))); // a throwing listener does not fail the offer
        assertEquals(1, calls.get());
        assertEquals(0, sub.poll().orElseThrow().frameSeq());
    }

    @Test
    void close_ignoresFurtherOffers_andClearsSlot() {
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
        assertTrue(sub.offer(frame(0)));
        sub.close();
        assertTrue(sub.isClosed());
        assertFalse(sub.offer(frame(1))); // closed → rejected
        assertTrue(sub.poll().isEmpty()); // held frame cleared on close
    }

    @Test
    void identity_isPreserved() {
        Runnable listener = () -> {
        };
        ViewOnlyViewerSubscription sub = subscription("sess", "stream-1", "vw-9", listener);
        assertEquals("sess", sub.sessionId());
        assertEquals("vw-9", sub.viewerId());
        assertSame("sess", sub.sessionId());
    }

    @Test
    void offerAfterClose_neverRepopulatesTheSlot() {
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
        sub.close();
        assertFalse(sub.offer(frame(7))); // offer that begins after close is rejected
        assertTrue(sub.poll().isEmpty()); // and the slot stays empty — no payload retained past close
    }

    @Test
    void renderAcknowledgementRequiresASentFrameAndRejectsReplay() {
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
        ViewOnlyFrame sent = frame(7);
        assertTrue(sub.markSent(sent, 20L));

        ViewOnlyViewerSubscription.RenderAcknowledgement ack =
                sub.acknowledgeRendered(7L, 70L).orElseThrow();
        assertEquals(7L, ack.frameSeq());
        assertEquals(63L, ack.endToEndAgeMillis());
        assertTrue(ack.firstRenderedFrame());
        assertEquals(1L, sub.renderedCount());
        assertTrue(sub.acknowledgeRendered(7L, 80L).isEmpty());
        assertTrue(sub.acknowledgeRendered(8L, 80L).isEmpty());

        assertTrue(sub.markSent(frame(8), 90L));
        ViewOnlyViewerSubscription.RenderAcknowledgement steady =
                sub.acknowledgeRendered(8L, 100L).orElseThrow();
        assertFalse(steady.firstRenderedFrame());
        assertEquals(2L, sub.renderedCount());
    }

    @Test
    void closeClearsPendingRenderAcknowledgementMetadata() {
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
        assertTrue(sub.markSent(frame(2), 20L));
        sub.close();
        assertTrue(sub.acknowledgeRendered(2L, 30L).isEmpty());
        assertFalse(sub.markSent(frame(3), 40L));
    }

    @Test
    void renderAcknowledgementRejectsNegativeSequenceAndBackwardClock() {
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
        assertTrue(sub.markSent(frame(2), 20L));
        assertTrue(sub.acknowledgeRendered(-1L, 30L).isEmpty());
        assertTrue(sub.acknowledgeRendered(2L, 19L).isEmpty());
        assertEquals(2L, sub.acknowledgeRendered(2L, 30L).orElseThrow().frameSeq());
    }

    @Test
    void pendingRenderMetadataEvictsTheEldestAfterTheBound() {
        ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
        for (long seq = 0; seq <= 32; seq++) {
            assertTrue(sub.markSent(frame(seq), 100L + seq));
        }

        assertTrue(sub.acknowledgeRendered(0L, 200L).isEmpty());
        assertEquals(1L, sub.acknowledgeRendered(1L, 200L).orElseThrow().frameSeq());
    }

    @Test
    void concurrentOfferAndClose_neverRetainAFrameAfterClose() throws InterruptedException {
        // the prior lock-free version had an offer/close interleaving that could re-store a frame after close;
        // with offer/close mutually atomic, a closed subscription must always end with an empty slot.
        for (int i = 0; i < 3000; i++) {
            ViewOnlyViewerSubscription sub = subscription("s1", "stream-1", "vw-1", null);
            CountDownLatch start = new CountDownLatch(1);
            Thread offerer = new Thread(() -> {
                awaitQuietly(start);
                sub.offer(frame(1));
            });
            Thread closer = new Thread(() -> {
                awaitQuietly(start);
                sub.close();
            });
            offerer.start();
            closer.start();
            start.countDown();
            offerer.join();
            closer.join();

            assertTrue(sub.isClosed());
            assertTrue(sub.poll().isEmpty(), "a closed subscription must never retain a frame");
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

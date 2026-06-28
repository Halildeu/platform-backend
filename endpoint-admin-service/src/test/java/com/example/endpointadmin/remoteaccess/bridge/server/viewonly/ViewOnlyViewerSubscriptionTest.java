package com.example.endpointadmin.remoteaccess.bridge.server.viewonly;

import com.google.protobuf.ByteString;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 #1580 — the single-slot latest-wins viewer mailbox (Codex 019f078a). */
class ViewOnlyViewerSubscriptionTest {

    private static ViewOnlyFrame frame(long seq) {
        return new ViewOnlyFrame("s1", "stream-1", seq, "image/png",
                ByteString.copyFromUtf8("f" + seq), false, seq);
    }

    @Test
    void latestWins_overwritesUnconsumedFrame() {
        ViewOnlyViewerSubscription sub = new ViewOnlyViewerSubscription("s1", "vw-1", null);
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
        ViewOnlyViewerSubscription sub = new ViewOnlyViewerSubscription("s1", "vw-1", () -> {
            calls.incrementAndGet();
            throw new RuntimeException("viewer listener fault must not break the DATA stream");
        });
        assertTrue(sub.offer(frame(0))); // a throwing listener does not fail the offer
        assertEquals(1, calls.get());
        assertEquals(0, sub.poll().orElseThrow().frameSeq());
    }

    @Test
    void close_ignoresFurtherOffers_andClearsSlot() {
        ViewOnlyViewerSubscription sub = new ViewOnlyViewerSubscription("s1", "vw-1", null);
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
        ViewOnlyViewerSubscription sub = new ViewOnlyViewerSubscription("sess", "vw-9", listener);
        assertEquals("sess", sub.sessionId());
        assertEquals("vw-9", sub.viewerId());
        assertSame("sess", sub.sessionId());
    }
}

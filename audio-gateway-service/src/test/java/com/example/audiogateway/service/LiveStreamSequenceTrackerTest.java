package com.example.audiogateway.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LiveStreamSequenceTrackerTest {

    @Test
    void acceptsContiguousFramesAndSuppressesReconnectDuplicates() {
        final LiveStreamSequenceTracker tracker = new LiveStreamSequenceTracker(4);

        assertThat(tracker.accept("SES-1", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
        assertThat(tracker.accept("SES-1", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.DUPLICATE);
        assertThat(tracker.accept("SES-1", -1L, 1L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
        assertThat(tracker.accept("SES-1", -1L, 3L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.GAP);
    }

    @Test
    void rollbackMakesAuditFailedFrameRetryable() {
        final LiveStreamSequenceTracker tracker = new LiveStreamSequenceTracker(4);

        assertThat(tracker.accept("SES-1", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
        tracker.rollbackAccepted("SES-1", 0L);

        assertThat(tracker.accept("SES-1", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
    }

    @Test
    void enforcesTrackedSessionCapacity() {
        final LiveStreamSequenceTracker tracker = new LiveStreamSequenceTracker(1);

        assertThat(tracker.accept("SES-1", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
        assertThat(tracker.accept("SES-2", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.CAPACITY_EXCEEDED);
        assertThat(tracker.trackedSessions()).isEqualTo(1);
    }

    @Test
    void releaseFreesCapacityForSubsequentSessionsAfterManyTerminatedConnections() {
        // Review finding: a long-lived gateway pod never released terminated
        // sessions' tracker state, so after capacity-many distinct sessions
        // connected and disconnected, every later session was rejected with
        // CAPACITY_EXCEEDED even though none of them were still active.
        final LiveStreamSequenceTracker tracker = new LiveStreamSequenceTracker(2);

        assertThat(tracker.accept("SES-1", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
        tracker.release("SES-1");
        assertThat(tracker.accept("SES-2", -1L, 0L))
                .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
        tracker.release("SES-2");
        assertThat(tracker.trackedSessions()).isEqualTo(0);

        // Open+close up to and beyond capacity, one at a time, as a long-lived
        // pod would over its lifetime — every one of these must still be
        // ACCEPTED, not CAPACITY_EXCEEDED, once its predecessor was released.
        for (int i = 0; i < 10; i++) {
            final String sessionId = "SES-loop-" + i;
            assertThat(tracker.accept(sessionId, -1L, 0L))
                    .as("session %s after %d prior terminated sessions", sessionId, i)
                    .isEqualTo(LiveStreamSequenceTracker.Outcome.ACCEPTED);
            tracker.release(sessionId);
        }
        assertThat(tracker.trackedSessions()).isEqualTo(0);
    }

    @Test
    void releaseIsSafeForUnknownSession() {
        final LiveStreamSequenceTracker tracker = new LiveStreamSequenceTracker(1);

        tracker.release("never-existed");

        assertThat(tracker.trackedSessions()).isEqualTo(0);
    }
}

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
}

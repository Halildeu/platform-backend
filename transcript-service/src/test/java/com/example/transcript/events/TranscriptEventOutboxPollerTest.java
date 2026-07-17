package com.example.transcript.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TranscriptEventOutboxPollerTest {

    private final TranscriptEventOutboxRepository repository =
            mock(TranscriptEventOutboxRepository.class);
    private final TranscriptMeetingEventPublisher publisher =
            mock(TranscriptMeetingEventPublisher.class);
    private final TranscriptEventOutbox row = mock(TranscriptEventOutbox.class);
    private final UUID rowId = UUID.randomUUID();
    private final UUID claimToken = UUID.randomUUID();
    private TranscriptEventOutboxPoller poller;

    @BeforeEach
    void setUp() {
        poller = new TranscriptEventOutboxPoller(
                repository, publisher, 10, 30_000, 3, 5_000, "test-owner", false);
        poller.setSelf(poller);
        when(row.getId()).thenReturn(rowId);
        when(row.getClaimToken()).thenReturn(claimToken);
        when(row.getEventKey()).thenReturn("meeting.transcript|session|meeting.transcript.ready|1");
        when(repository.claimBatch(any(), any(), eq("test-owner"), any(), eq(10)))
                .thenReturn(1);
        when(repository.findByClaimToken(any())).thenReturn(List.of(row));
    }

    @Test
    void successfulPublishUsesLeaseFenceBeforeMarkingPublished() {
        when(repository.markPublishedFenced(eq(rowId), eq(claimToken), any()))
                .thenReturn(1);

        poller.runCycle();

        verify(publisher).publish(TranscriptMeetingEventMessage.from(row));
        verify(repository).markPublishedFenced(eq(rowId), eq(claimToken), any());
        verify(repository, never()).markFailedFenced(
                any(), any(), any(), any(Integer.class), any(), any());
    }

    @Test
    void publishFailureReturnsRowToBoundedFailurePath() {
        RuntimeException failure = new IllegalStateException("redis unavailable");
        org.mockito.Mockito.doThrow(failure).when(publisher).publish(any());
        when(repository.markFailedFenced(
                eq(rowId), eq(claimToken), eq("IllegalStateException"), eq(3), any(), any()))
                .thenReturn(1);

        poller.runCycle();

        verify(repository).markFailedFenced(
                eq(rowId), eq(claimToken), eq("IllegalStateException"), eq(3), any(), any());
        verify(repository, never()).markPublishedFenced(any(), any(), any());
    }

    @Test
    void staleLeaseRecoverySchedulesRetryWithoutConsumingFailureBudget() {
        poller.recoverStaleLeases();

        ArgumentCaptor<Instant> recoveredAt = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> retryAt = ArgumentCaptor.forClass(Instant.class);
        verify(repository).recoverStaleLeases(recoveredAt.capture(), retryAt.capture());
        assertThat(Duration.between(recoveredAt.getValue(), retryAt.getValue()))
                .isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void emptyClaimProducesNoTransportEffect() {
        when(repository.claimBatch(any(), any(), eq("test-owner"), any(), eq(10)))
                .thenReturn(0);

        poller.runCycle();

        verify(repository, never()).findByClaimToken(any());
        verify(publisher, never()).publish(any());
    }
}

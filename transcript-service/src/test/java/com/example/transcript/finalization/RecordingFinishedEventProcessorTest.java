package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.model.TranscriptMeetingEventInbox;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptMeetingEventInboxRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.service.SessionErasureFence;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RecordingFinishedEventProcessorTest {

    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEETING = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-07-17T10:05:01Z");
    private static final String SOURCE_SESSION = "SES-desktop-1";
    private static final String EVENT_KEY =
            "meeting.recording|" + SESSION + "|meeting.recording.finished|1";
    private static final String SHA = "a".repeat(64);

    private final TranscriptMeetingEventInboxRepository inbox =
            mock(TranscriptMeetingEventInboxRepository.class);
    private final TranscriptSessionAssociationRepository associations =
            mock(TranscriptSessionAssociationRepository.class);
    private final TranscriptFinalizationStateMachine stateMachine =
            mock(TranscriptFinalizationStateMachine.class);
    private final SessionErasureFence erasureFence = mock(SessionErasureFence.class);
    private RecordingFinishedEventProcessor processor;

    @BeforeEach
    void setUp() {
        processor = new RecordingFinishedEventProcessor(
                inbox, associations, stateMachine, erasureFence,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void firstOccurrenceEnrollsResolvedAssociationAndMarksInboxProcessed() {
        RecordingFinishedEvent event = event();
        TranscriptMeetingEventInbox stored = matchingInbox(null);
        TranscriptSessionAssociation association = matchingAssociation(SESSION);
        when(inbox.insertIfAbsent(any(), eq(EVENT_KEY), any(), eq(SHA),
                eq(TENANT), eq(MEETING), eq(SESSION), eq(SOURCE_SESSION), eq(NOW)))
                .thenReturn(1);
        when(inbox.findByEventKey(EVENT_KEY)).thenReturn(Optional.of(stored));
        when(associations.findSourceForUpdate(TENANT, MEETING, "DIRECT_STT", SOURCE_SESSION))
                .thenReturn(Optional.of(association));
        when(inbox.markProcessed(EVENT_KEY, NOW)).thenReturn(1);

        assertThat(processor.process(event))
                .isEqualTo(RecordingFinishedEventProcessor.ProcessResult.PROCESSED);

        verify(associations).insertResolvedIfAbsent(
                any(), eq(TENANT), eq(MEETING), eq("DIRECT_STT"),
                eq(SOURCE_SESSION), eq(SESSION), eq(NOW));
        verify(stateMachine).observeRecordingFinished(
                association, event.finishedAt(), NOW);
        verify(associations).saveAndFlush(association);
        verify(inbox).markProcessed(EVENT_KEY, NOW);
    }

    @Test
    void completedReplayIsDuplicateWithoutReenteringStateMachine() {
        TranscriptMeetingEventInbox stored = matchingInbox(NOW.minusSeconds(1));
        when(inbox.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(inbox.findByEventKey(EVENT_KEY)).thenReturn(Optional.of(stored));

        assertThat(processor.process(event()))
                .isEqualTo(RecordingFinishedEventProcessor.ProcessResult.DUPLICATE);

        verify(associations, never()).insertResolvedIfAbsent(any(), any(), any(), any(), any(), any(), any());
        verify(stateMachine, never()).observeRecordingFinished(any(), any(), any());
    }

    @Test
    void eventKeyReuseWithDifferentScopeFailsClosed() {
        TranscriptMeetingEventInbox stored = matchingInbox(null);
        when(stored.getPayloadSha256()).thenReturn("b".repeat(64));
        when(inbox.insertIfAbsent(any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(0);
        when(inbox.findByEventKey(EVENT_KEY)).thenReturn(Optional.of(stored));

        assertThatThrownBy(() -> processor.process(event()))
                .isInstanceOf(RecordingFinishedEventProcessor.RecordingFinishedEventConflictException.class)
                .hasMessage("INBOX_KEY_DIVERGENCE");
        verify(stateMachine, never()).observeRecordingFinished(any(), any(), any());
    }

    private RecordingFinishedEvent event() {
        return new RecordingFinishedEvent(
                EVENT_KEY, SHA, TENANT, MEETING, SESSION, SOURCE_SESSION,
                Instant.parse("2026-07-17T10:05:00Z"));
    }

    private TranscriptMeetingEventInbox matchingInbox(Instant processedAt) {
        TranscriptMeetingEventInbox stored = mock(TranscriptMeetingEventInbox.class);
        when(stored.getPayloadSha256()).thenReturn(SHA);
        when(stored.getTenantId()).thenReturn(TENANT);
        when(stored.getMeetingId()).thenReturn(MEETING);
        when(stored.getSessionId()).thenReturn(SESSION);
        when(stored.getSourceSessionId()).thenReturn(SOURCE_SESSION);
        when(stored.getProcessedAt()).thenReturn(processedAt);
        return stored;
    }

    private TranscriptSessionAssociation matchingAssociation(UUID sessionId) {
        TranscriptSessionAssociation association = mock(TranscriptSessionAssociation.class);
        when(association.getStatus()).thenReturn(TranscriptSessionAssociationStatus.RESOLVED);
        when(association.getTenantId()).thenReturn(TENANT);
        when(association.getMeetingId()).thenReturn(MEETING);
        when(association.getSourceSystem()).thenReturn("DIRECT_STT");
        when(association.getSourceSessionId()).thenReturn(SOURCE_SESSION);
        when(association.getSessionId()).thenReturn(sessionId);
        return association;
    }
}

package com.example.transcript.finalization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.model.TranscriptEventOutbox;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptFinalizationState;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptEventOutboxRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class TranscriptQuiescentFinalizationProcessorTest {

    private static final UUID ASSOCIATION_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID TENANT = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID MEETING = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID SESSION = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final Instant NOW = Instant.parse("2026-07-17T10:15:00Z");

    private final TranscriptSessionAssociationRepository associations =
            mock(TranscriptSessionAssociationRepository.class);
    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final TranscriptFinalizationRepository finalizations = mock(TranscriptFinalizationRepository.class);
    private final TranscriptEventOutboxRepository outbox = mock(TranscriptEventOutboxRepository.class);
    private TranscriptQuiescentFinalizationProcessor processor;

    @BeforeEach
    void setUp() {
        processor = processorAt(NOW);
    }

    @Test
    void exactPt6mMinWaitBoundaryDoesNotPublishBeforeDueAndPublishesAtDue() {
        Instant observedAt = NOW.minus(Duration.ofMinutes(6));
        Instant minWaitAt = observedAt.plus(Duration.ofMinutes(6));
        TranscriptSessionAssociation association = dueAssociation(observedAt.plus(Duration.ofMinutes(15)));
        association.setMinWaitAt(minWaitAt);
        association.setQuiescenceDueAt(minWaitAt);
        when(associations.findByIdForUpdate(ASSOCIATION_ID)).thenReturn(Optional.of(association));
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of(draftSegment("canonical phrase")));

        assertThat(association.getStatus()).isEqualTo(TranscriptSessionAssociationStatus.RESOLVED);
        assertThat(association.getSessionId()).isEqualTo(SESSION);
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.QUIESCING);
        assertThat(association.getQuiescenceDueAt()).isEqualTo(minWaitAt);

        assertThat(processorAt(minWaitAt.minus(1, ChronoUnit.MICROS)).process(ASSOCIATION_ID))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.NOT_DUE);
        verify(outbox, never()).save(any());
        verify(finalizations, never()).save(any());

        assertThat(processorAt(minWaitAt).process(ASSOCIATION_ID))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.READY);
        verify(outbox, times(1)).save(any());
        verify(finalizations, times(1)).save(any());

        assertThat(processorAt(minWaitAt).process(ASSOCIATION_ID))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.NOT_DUE);
        verify(outbox, times(1)).save(any());
        verify(finalizations, times(1)).save(any());
    }

    private TranscriptQuiescentFinalizationProcessor processorAt(Instant now) {
        TranscriptFinalizationProperties properties = new TranscriptFinalizationProperties();
        return new TranscriptQuiescentFinalizationProcessor(
                associations, segments, finalizations, outbox,
                new TranscriptFinalizationStateMachine(properties),
                new TranscriptSnapshotHasher(), Clock.fixed(now, ZoneOffset.UTC));
    }

    @Test
    void emptyTranscriptWaitsUntilMaxDeadlineWithoutPublishing() {
        TranscriptSessionAssociation association = dueAssociation(NOW.plusSeconds(60));
        when(associations.findByIdForUpdate(ASSOCIATION_ID)).thenReturn(Optional.of(association));
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION)).thenReturn(List.of());

        assertThat(processor.process(ASSOCIATION_ID))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.WAITING_FOR_CONTENT);

        assertThat(association.getQuiescenceDueAt()).isEqualTo(NOW.plusSeconds(60));
        verify(outbox, never()).save(any());
        verify(finalizations, never()).save(any());
    }

    @Test
    void zeroValidSegmentsAtDeadlinePublishesFailureButNeverReady() {
        TranscriptSessionAssociation association = dueAssociation(NOW);
        when(associations.findByIdForUpdate(ASSOCIATION_ID)).thenReturn(Optional.of(association));
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION)).thenReturn(List.of());

        assertThat(processor.process(ASSOCIATION_ID))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.FAILED);

        ArgumentCaptor<TranscriptEventOutbox> event = ArgumentCaptor.forClass(TranscriptEventOutbox.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("meeting.transcript.failed");
        assertThat(event.getValue().getPayload())
                .contains("NO_VALID_SEGMENTS_BEFORE_DEADLINE")
                .doesNotContain("transcriptText");
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.TIMED_OUT);
        verify(finalizations, never()).save(any());
    }

    @Test
    void validSnapshotPersistsIntegrityRowAndReadyEventAtomically() {
        TranscriptSessionAssociation association = dueAssociation(NOW.plusSeconds(60));
        TranscriptSegment segment = draftSegment("canonical phrase");
        when(associations.findByIdForUpdate(ASSOCIATION_ID)).thenReturn(Optional.of(association));
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of(segment));

        assertThat(processor.process(ASSOCIATION_ID))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.READY);

        ArgumentCaptor<TranscriptFinalization> finalization =
                ArgumentCaptor.forClass(TranscriptFinalization.class);
        ArgumentCaptor<TranscriptEventOutbox> event = ArgumentCaptor.forClass(TranscriptEventOutbox.class);
        verify(finalizations).save(finalization.capture());
        verify(outbox).save(event.capture());
        assertThat(finalization.getValue().getFinalizationVersion()).isEqualTo(1);
        assertThat(finalization.getValue().getSegmentCount()).isEqualTo(1);
        assertThat(finalization.getValue().getSnapshotSha256()).matches("[0-9a-f]{64}");
        assertThat(event.getValue().getEventType()).isEqualTo("meeting.transcript.ready");
        assertThat(event.getValue().getPayload())
                .contains("\"segmentCount\":1")
                .doesNotContain("canonical phrase");
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.FINALIZED);
        assertThat(association.getFinalizationVersion()).isEqualTo(1);
    }

    @Test
    void invalidCanonicalSegmentFailsClosedWithoutReadyEvent() {
        TranscriptSessionAssociation association = dueAssociation(NOW.plusSeconds(60));
        TranscriptSegment invalid = draftSegment(" ");
        when(associations.findByIdForUpdate(ASSOCIATION_ID)).thenReturn(Optional.of(association));
        when(segments.findCanonicalSessionForUpdate(TENANT, MEETING, SESSION))
                .thenReturn(List.of(invalid));

        assertThat(processor.process(ASSOCIATION_ID))
                .isEqualTo(TranscriptQuiescentFinalizationProcessor.Outcome.INVALID_SNAPSHOT);

        ArgumentCaptor<TranscriptEventOutbox> event = ArgumentCaptor.forClass(TranscriptEventOutbox.class);
        verify(outbox).save(event.capture());
        assertThat(event.getValue().getEventType()).isEqualTo("meeting.transcript.failed");
        assertThat(event.getValue().getPayload())
                .contains("INVALID_CANONICAL_SEGMENT")
                .doesNotContain("textDraft", "textFinal");
        assertThat(association.getFinalizationState()).isEqualTo(TranscriptFinalizationState.TIMED_OUT);
        assertThat(association.getFinalizationErrorCode()).isEqualTo("INVALID_CANONICAL_SEGMENT");
        verify(finalizations, never()).save(any());
    }

    private TranscriptSessionAssociation dueAssociation(Instant maxWaitAt) {
        TranscriptSessionAssociation association = new TranscriptSessionAssociation();
        ReflectionTestUtils.setField(association, "id", ASSOCIATION_ID);
        ReflectionTestUtils.setField(association, "tenantId", TENANT);
        ReflectionTestUtils.setField(association, "orgId", TENANT);
        ReflectionTestUtils.setField(association, "meetingId", MEETING);
        ReflectionTestUtils.setField(association, "sourceSystem", "DIRECT_STT");
        ReflectionTestUtils.setField(association, "sourceSessionId", "SES-desktop-1");
        ReflectionTestUtils.setField(association, "sessionId", SESSION);
        ReflectionTestUtils.setField(association, "status", TranscriptSessionAssociationStatus.RESOLVED);
        association.setFinalizationState(TranscriptFinalizationState.QUIESCING);
        association.setFinalizationVersion(0);
        association.setFinalizationCycleVersion(1);
        association.setQuiescenceDueAt(NOW);
        association.setMaxWaitAt(maxWaitAt);
        return association;
    }

    private TranscriptSegment draftSegment(String text) {
        TranscriptSegment segment = new TranscriptSegment();
        segment.setId(UUID.fromString("00000000-0000-0000-0000-000000000020"));
        segment.setTenantId(TENANT);
        segment.setOrgId(TENANT);
        segment.setMeetingId(MEETING);
        segment.setSessionId(SESSION);
        segment.setStatus(TranscriptSegmentStatus.DRAFT);
        segment.setTextDraft(text);
        segment.setStartTime(0.0);
        segment.setEndTime(1.0);
        segment.setSourceSystem("DIRECT_STT");
        segment.setSourceSessionId("SES-desktop-1");
        segment.setSourceWindowSeq(1L);
        segment.setSourceFirstChunkSeq(1L);
        segment.setSourceLastChunkSeq(2L);
        segment.setSourceChunkSeq(2L);
        return segment;
    }
}

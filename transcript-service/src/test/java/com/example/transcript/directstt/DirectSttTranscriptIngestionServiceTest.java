package com.example.transcript.directstt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.finalization.TranscriptFinalizationStateMachine;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DirectSttTranscriptIngestionServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final UUID MEETING = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final UUID SEGMENT = UUID.randomUUID();

    private final TranscriptSegmentRepository segments = mock(TranscriptSegmentRepository.class);
    private final TranscriptSessionAssociationRepository associations =
            mock(TranscriptSessionAssociationRepository.class);
    private final TranscriptSessionAssociation association = mock(TranscriptSessionAssociation.class);
    private final TranscriptFinalizationStateMachine finalizationStateMachine =
            mock(TranscriptFinalizationStateMachine.class);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-17T14:00:00Z"), ZoneOffset.UTC);
    private final DirectSttTranscriptIngestionService service =
            new DirectSttTranscriptIngestionService(
                    segments, associations, finalizationStateMachine, clock);

    @BeforeEach
    void resolvedAssociation() {
        when(associations.findSourceForUpdate(
                TENANT, MEETING, DirectSttTranscriptResultEvent.SOURCE_SYSTEM, "SES-abc"))
                .thenReturn(Optional.of(association));
        when(association.getStatus()).thenReturn(TranscriptSessionAssociationStatus.RESOLVED);
        when(association.getSessionId()).thenReturn(SESSION);
    }

    @Test
    void createsDraftWithSourceWindowAndCanonicalSessionUuid() {
        DirectSttTranscriptResultEvent event = event("1-0", 2L, 3L, 5L, "merhaba dunya");
        when(segments.findDirectSttSourceWindow(TENANT, MEETING, "SES-abc", 2L))
                .thenReturn(Optional.empty());
        ArgumentCaptor<TranscriptSegment> saved = ArgumentCaptor.forClass(TranscriptSegment.class);
        when(segments.saveAndFlush(saved.capture())).thenAnswer(invocation -> {
            TranscriptSegment row = invocation.getArgument(0);
            row.setId(SEGMENT);
            return row;
        });

        var result = service.upsert(event, SESSION);

        assertThat(result.id()).isEqualTo(SEGMENT);
        assertThat(saved.getValue().getTenantId()).isEqualTo(TENANT);
        assertThat(saved.getValue().getMeetingId()).isEqualTo(MEETING);
        assertThat(saved.getValue().getSourceSessionId()).isEqualTo("SES-abc");
        assertThat(saved.getValue().getSessionId()).isEqualTo(SESSION);
        assertThat(saved.getValue().getSourceWindowSeq()).isEqualTo(2L);
        assertThat(saved.getValue().getSourceFirstChunkSeq()).isEqualTo(3L);
        assertThat(saved.getValue().getSourceLastChunkSeq()).isEqualTo(5L);
        assertThat(saved.getValue().getSourceChunkSeq()).isEqualTo(5L);
        assertThat(saved.getValue().getTextDraft()).isEqualTo("merhaba dunya");
        assertThat(saved.getValue().getStatus()).isEqualTo(TranscriptSegmentStatus.DRAFT);
        verify(finalizationStateMachine).recordDistinctContent(
                association, Instant.parse("2026-07-17T14:00:00Z"));
        verify(associations).saveAndFlush(association);
    }

    @Test
    void rejectsCanonicalUuidThatDoesNotMatchPinnedAssociation() {
        assertThatThrownBy(() -> service.upsert(
                event("1-0", 2L, 3L, 5L, "draft"), UUID.randomUUID()))
                .isInstanceOf(DirectSttTranscriptIngestionService.SessionAssociationNotResolvedException.class);
        verify(segments, never()).saveAndFlush(any());
    }

    @Test
    void sameWindowSameContentReplayIsNoOp() {
        TranscriptSegment existing = storedSegment(2L, 3L, 5L);
        existing.setTextDraft("original");
        when(segments.findDirectSttSourceWindow(TENANT, MEETING, "SES-abc", 2L))
                .thenReturn(Optional.of(existing));

        var result = service.upsert(
                event("different-entry", 2L, 3L, 5L, "original"), SESSION);

        assertThat(result.textDraft()).isEqualTo("original");
        verify(segments, never()).saveAndFlush(any());
        verify(finalizationStateMachine, never()).recordDistinctContent(any(), any());
    }

    @Test
    void sameWindowDifferentTextFailsClosed() {
        TranscriptSegment existing = storedSegment(2L, 3L, 5L);
        existing.setTextDraft("original");
        when(segments.findDirectSttSourceWindow(TENANT, MEETING, "SES-abc", 2L))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upsert(
                event("2-0", 2L, 3L, 5L, "conflicting"), SESSION))
                .isInstanceOf(
                        DirectSttTranscriptIngestionService.SourceWindowReplayConflictException.class);
        verify(segments, never()).saveAndFlush(any());
    }

    @Test
    void sameWindowDifferentChunkRangeFailsClosed() {
        TranscriptSegment existing = storedSegment(2L, 3L, 5L);
        existing.setTextDraft("original");
        when(segments.findDirectSttSourceWindow(TENANT, MEETING, "SES-abc", 2L))
                .thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.upsert(
                event("2-0", 2L, 4L, 5L, "original"), SESSION))
                .isInstanceOf(
                        DirectSttTranscriptIngestionService.SourceWindowReplayConflictException.class);
        verify(segments, never()).saveAndFlush(any());
    }

    @Test
    void postFinalizationSameContentReplayReturnsStoredSegmentWithoutMutation() {
        when(association.getFinalizationVersion()).thenReturn(1L);
        TranscriptSegment existing = storedSegment(2L, 3L, 5L);
        existing.setTextDraft("original");
        existing.setTextFinal("human final");
        existing.setStatus(TranscriptSegmentStatus.FINALIZED);
        when(segments.findDirectSttSourceWindow(TENANT, MEETING, "SES-abc", 2L))
                .thenReturn(Optional.of(existing));

        var result = service.upsert(
                event("2-0", 2L, 3L, 5L, "original"), SESSION);

        assertThat(result.textDraft()).isEqualTo("original");
        assertThat(result.textFinal()).isEqualTo("human final");
        verify(segments, never()).saveAndFlush(any());
    }

    @Test
    void postFinalizationNewWindowIsPersistedAndStartsAnotherCycle() {
        when(association.getFinalizationVersion()).thenReturn(1L);
        when(segments.findDirectSttSourceWindow(TENANT, MEETING, "SES-abc", 3L))
                .thenReturn(Optional.empty());
        when(segments.saveAndFlush(any())).thenAnswer(invocation -> {
            TranscriptSegment row = invocation.getArgument(0);
            row.setId(SEGMENT);
            return row;
        });

        var result = service.upsert(
                event("2-0", 3L, 6L, 6L, "late window"), SESSION);

        assertThat(result.textDraft()).isEqualTo("late window");
        verify(finalizationStateMachine).recordDistinctContent(
                association, Instant.parse("2026-07-17T14:00:00Z"));
    }

    private TranscriptSegment storedSegment(
            long windowSeq, long firstChunkSeq, long lastChunkSeq) {
        TranscriptSegment row = new TranscriptSegment();
        row.setId(SEGMENT);
        row.setTenantId(TENANT);
        row.setOrgId(TENANT);
        row.setMeetingId(MEETING);
        row.setSessionId(SESSION);
        row.setSourceSystem(DirectSttTranscriptResultEvent.SOURCE_SYSTEM);
        row.setSourceSessionId("SES-abc");
        row.setSourceChunkSeq(lastChunkSeq);
        row.setSourceWindowSeq(windowSeq);
        row.setSourceFirstChunkSeq(firstChunkSeq);
        row.setSourceLastChunkSeq(lastChunkSeq);
        row.setSourceSha256("deadbeefcafe0000sha");
        row.setStartTime(1.25d);
        row.setEndTime(2.45d);
        row.setStatus(TranscriptSegmentStatus.DRAFT);
        return row;
    }

    private DirectSttTranscriptResultEvent event(
            String entryId,
            long windowSeq,
            long firstChunkSeq,
            long lastChunkSeq,
            String text) {
        return new DirectSttTranscriptResultEvent(
                entryId, TENANT, TENANT.toString(), "7", MEETING, "SES-abc",
                windowSeq, firstChunkSeq, lastChunkSeq, 1_250L,
                "corr-direct-stt", "deadbeefcafe0000sha", text, 1.2d);
    }
}

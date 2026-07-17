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
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
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
    private final DirectSttTranscriptIngestionService service =
            new DirectSttTranscriptIngestionService(segments, associations);

    @BeforeEach
    void resolvedAssociation() {
        when(associations.findSourceForUpdate(
                TENANT, MEETING, DirectSttTranscriptResultEvent.SOURCE_SYSTEM, "SES-abc"))
                .thenReturn(Optional.of(association));
        when(association.getStatus()).thenReturn(TranscriptSessionAssociationStatus.RESOLVED);
        when(association.getSessionId()).thenReturn(SESSION);
    }

    @Test
    void createsDraftWithSourceIdAndCanonicalSessionUuid() {
        DirectSttTranscriptResultEvent event = event("1-0", 5L, "merhaba dunya");
        when(segments.findDirectSttSourceChunk(TENANT, MEETING, "SES-abc", 5L))
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
        assertThat(saved.getValue().getTextDraft()).isEqualTo("merhaba dunya");
        assertThat(saved.getValue().getStatus()).isEqualTo(TranscriptSegmentStatus.DRAFT);
    }

    @Test
    void rejectsCanonicalUuidThatDoesNotMatchPinnedAssociation() {
        assertThatThrownBy(() -> service.upsert(event("1-0", 5L, "draft"), UUID.randomUUID()))
                .isInstanceOf(DirectSttTranscriptIngestionService.SessionAssociationNotResolvedException.class);
        verify(segments, never()).saveAndFlush(any());
    }

    @Test
    void postFinalizationReplayReturnsStoredSegmentWithoutMutation() {
        when(association.getFinalizationVersion()).thenReturn(1L);
        TranscriptSegment existing = storedSegment(5L);
        existing.setTextDraft("original");
        existing.setTextFinal("human final");
        existing.setStatus(TranscriptSegmentStatus.FINALIZED);
        when(segments.findDirectSttSourceChunk(TENANT, MEETING, "SES-abc", 5L))
                .thenReturn(Optional.of(existing));

        var result = service.upsert(event("2-0", 5L, "late replay"), SESSION);

        assertThat(result.textDraft()).isEqualTo("original");
        assertThat(result.textFinal()).isEqualTo("human final");
        verify(segments, never()).saveAndFlush(any());
    }

    @Test
    void postFinalizationNewChunkIsRejected() {
        when(association.getFinalizationVersion()).thenReturn(1L);
        when(segments.findDirectSttSourceChunk(TENANT, MEETING, "SES-abc", 6L))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.upsert(event("2-0", 6L, "late chunk"), SESSION))
                .isInstanceOf(DirectSttTranscriptIngestionService.TranscriptAlreadyFinalizedException.class);
        verify(segments, never()).saveAndFlush(any());
    }

    private TranscriptSegment storedSegment(long chunkSeq) {
        TranscriptSegment row = new TranscriptSegment();
        row.setId(SEGMENT);
        row.setTenantId(TENANT);
        row.setOrgId(TENANT);
        row.setMeetingId(MEETING);
        row.setSessionId(SESSION);
        row.setSourceSystem(DirectSttTranscriptResultEvent.SOURCE_SYSTEM);
        row.setSourceSessionId("SES-abc");
        row.setSourceChunkSeq(chunkSeq);
        row.setStartTime(1.25d);
        row.setEndTime(2.45d);
        row.setStatus(TranscriptSegmentStatus.DRAFT);
        return row;
    }

    private DirectSttTranscriptResultEvent event(String entryId, long chunkSeq, String text) {
        return new DirectSttTranscriptResultEvent(
                entryId, TENANT, TENANT.toString(), "7", MEETING, "SES-abc",
                chunkSeq, 1_250L, "corr-direct-stt", "deadbeefcafe0000sha", text, 1.2d);
    }
}

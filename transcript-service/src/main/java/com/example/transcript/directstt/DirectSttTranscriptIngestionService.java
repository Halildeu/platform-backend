package com.example.transcript.directstt;

import com.example.transcript.dto.TranscriptSegmentDto;
import com.example.transcript.finalization.TranscriptFinalizationStateMachine;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.service.SessionErasureFence;
import com.example.transcript.service.SessionErasureFence.UUIDScope;
import com.example.transcript.service.SourceWindowRetentionFence;
import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Canonical, association-locked Direct-STT segment write path. */
@Service
public class DirectSttTranscriptIngestionService {

    private final TranscriptSegmentRepository segments;
    private final TranscriptSessionAssociationRepository associations;
    private final TranscriptFinalizationStateMachine finalizationStateMachine;
    private final SessionErasureFence erasureFence;
    private final SourceWindowRetentionFence retentionFence;
    private final Clock clock;

    public DirectSttTranscriptIngestionService(
            TranscriptSegmentRepository segments,
            TranscriptSessionAssociationRepository associations,
            TranscriptFinalizationStateMachine finalizationStateMachine,
            SessionErasureFence erasureFence,
            SourceWindowRetentionFence retentionFence,
            Clock transcriptFinalizationClock) {
        this.segments = segments;
        this.associations = associations;
        this.finalizationStateMachine = finalizationStateMachine;
        this.erasureFence = erasureFence;
        this.retentionFence = retentionFence;
        this.clock = transcriptFinalizationClock;
    }

    @Transactional
    public TranscriptSegmentDto upsert(
            DirectSttTranscriptResultEvent event, UUID canonicalSessionId) {
        UUIDScope scope = new UUIDScope(event.tenantId(), event.meetingId(), canonicalSessionId);
        erasureFence.lock(
                SessionErasureFence.canonicalKey(scope),
                SessionErasureFence.sourceKey(
                        event.tenantId(), event.meetingId(), event.sourceSessionId()));
        erasureFence.rejectErased(scope, event.sourceSessionId());
        retentionFence.lockAndRejectRetained(
                event.tenantId(), event.meetingId(), event.sourceSessionId(), event.windowSeq());
        TranscriptSessionAssociation association = associations.findSourceForUpdate(
                        event.tenantId(), event.meetingId(), DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                        event.sourceSessionId())
                .orElseThrow(() -> new SessionAssociationNotResolvedException());
        if (association.getStatus() != TranscriptSessionAssociationStatus.RESOLVED
                || association.getSessionId() == null
                || !association.getSessionId().equals(canonicalSessionId)) {
            throw new SessionAssociationNotResolvedException();
        }

        TranscriptSegment segment = segments.findDirectSttSourceWindow(
                        event.tenantId(), event.meetingId(),
                        event.sourceSessionId(), event.windowSeq())
                .orElse(null);
        if (segment != null) {
            if (!event.meetingId().equals(segment.getMeetingId())
                    || !canonicalSessionId.equals(segment.getSessionId())) {
                throw new SessionAssociationConflictException();
            }
            if (!sameSourceContent(segment, event)) {
                throw new SourceWindowReplayConflictException();
            }
            return TranscriptSegmentDto.from(segment);
        } else {
            segment = new TranscriptSegment();
            segment.setTenantId(event.tenantId());
            segment.setOrgId(event.tenantId());
            segment.setMeetingId(event.meetingId());
            segment.setSessionId(canonicalSessionId);
            segment.setSourceSystem(DirectSttTranscriptResultEvent.SOURCE_SYSTEM);
            segment.setSourceSessionId(event.sourceSessionId());
            segment.setSourceChunkSeq(event.lastChunkSeq());
            segment.setSourceWindowSeq(event.windowSeq());
            segment.setSourceFirstChunkSeq(event.firstChunkSeq());
            segment.setSourceLastChunkSeq(event.lastChunkSeq());
        }

        double startSeconds = event.chunkStartedAtMs() / 1000.0d;
        double durationSeconds = durationSeconds(event);
        segment.setStartTime(startSeconds);
        segment.setEndTime(startSeconds + durationSeconds);
        segment.setTextDraft(event.textDraft());
        segment.setTextFinal(null);
        segment.setConfidence(null);
        segment.setStatus(TranscriptSegmentStatus.DRAFT);
        segment.setSourceEventId(event.entryId());
        segment.setSourceSha256(event.sha256());
        segment.setSourceCorrelationId(event.correlationId());
        TranscriptSegment saved = segments.saveAndFlush(segment);
        finalizationStateMachine.recordDistinctContent(association, clock.instant());
        associations.saveAndFlush(association);
        return TranscriptSegmentDto.from(saved);
    }

    private boolean sameSourceContent(
            TranscriptSegment segment, DirectSttTranscriptResultEvent event) {
        double startSeconds = event.chunkStartedAtMs() / 1000.0d;
        double endSeconds = startSeconds + durationSeconds(event);
        return DirectSttTranscriptResultEvent.SOURCE_SYSTEM.equals(segment.getSourceSystem())
                && event.tenantId().equals(segment.getTenantId())
                && event.sourceSessionId().equals(segment.getSourceSessionId())
                && Objects.equals(segment.getSourceWindowSeq(), event.windowSeq())
                && Objects.equals(segment.getSourceFirstChunkSeq(), event.firstChunkSeq())
                && Objects.equals(segment.getSourceLastChunkSeq(), event.lastChunkSeq())
                && Objects.equals(segment.getSourceChunkSeq(), event.lastChunkSeq())
                && Objects.equals(segment.getStartTime(), startSeconds)
                && Objects.equals(segment.getEndTime(), endSeconds)
                && Objects.equals(segment.getTextDraft(), event.textDraft())
                && Objects.equals(segment.getSourceSha256(), event.sha256());
    }

    private double durationSeconds(DirectSttTranscriptResultEvent event) {
        return event.durationSeconds() != null ? event.durationSeconds() : 0.0d;
    }

    public static class SessionAssociationNotResolvedException extends IllegalStateException {
        public SessionAssociationNotResolvedException() {
            super("canonical session association is not resolved");
        }
    }

    public static class SessionAssociationConflictException extends IllegalStateException {
        public SessionAssociationConflictException() {
            this("canonical session association conflicts with the stored segment");
        }

        protected SessionAssociationConflictException(String message) {
            super(message);
        }
    }

    public static class SourceWindowReplayConflictException
            extends SessionAssociationConflictException {
        public SourceWindowReplayConflictException() {
            super("source window replay conflicts with the stored segment");
        }
    }

}

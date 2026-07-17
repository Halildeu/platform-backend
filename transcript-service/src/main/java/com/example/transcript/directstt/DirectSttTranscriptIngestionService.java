package com.example.transcript.directstt;

import com.example.transcript.dto.TranscriptSegmentDto;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionAssociationStatus;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Canonical, association-locked Direct-STT segment write path. */
@Service
public class DirectSttTranscriptIngestionService {

    private final TranscriptSegmentRepository segments;
    private final TranscriptSessionAssociationRepository associations;

    public DirectSttTranscriptIngestionService(
            TranscriptSegmentRepository segments,
            TranscriptSessionAssociationRepository associations) {
        this.segments = segments;
        this.associations = associations;
    }

    @Transactional
    public TranscriptSegmentDto upsert(
            DirectSttTranscriptResultEvent event, UUID canonicalSessionId) {
        TranscriptSessionAssociation association = associations.findSourceForUpdate(
                        event.tenantId(), event.meetingId(), DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                        event.sourceSessionId())
                .orElseThrow(() -> new SessionAssociationNotResolvedException());
        if (association.getStatus() != TranscriptSessionAssociationStatus.RESOLVED
                || association.getSessionId() == null
                || !association.getSessionId().equals(canonicalSessionId)) {
            throw new SessionAssociationNotResolvedException();
        }

        TranscriptSegment segment = segments.findDirectSttSourceChunk(
                        event.tenantId(), event.meetingId(),
                        event.sourceSessionId(), event.chunkSeq())
                .orElse(null);
        if (segment != null) {
            if (!event.meetingId().equals(segment.getMeetingId())
                    || !canonicalSessionId.equals(segment.getSessionId())) {
                throw new SessionAssociationConflictException();
            }
            // A replay after finalization is harmless and must not rewrite final text.
            if (association.getFinalizationVersion() > 0) {
                return TranscriptSegmentDto.from(segment);
            }
        } else {
            if (association.getFinalizationVersion() > 0) {
                throw new TranscriptAlreadyFinalizedException();
            }
            segment = new TranscriptSegment();
            segment.setTenantId(event.tenantId());
            segment.setOrgId(event.tenantId());
            segment.setMeetingId(event.meetingId());
            segment.setSessionId(canonicalSessionId);
            segment.setSourceSystem(DirectSttTranscriptResultEvent.SOURCE_SYSTEM);
            segment.setSourceSessionId(event.sourceSessionId());
            segment.setSourceChunkSeq(event.chunkSeq());
        }

        if (segment.getId() == null
                || (segment.getStatus() == TranscriptSegmentStatus.DRAFT
                    && segment.getTextFinal() == null)) {
            double startSeconds = event.chunkStartedAtMs() / 1000.0d;
            double durationSeconds = event.durationSeconds() != null ? event.durationSeconds() : 0.0d;
            segment.setStartTime(startSeconds);
            segment.setEndTime(startSeconds + durationSeconds);
            segment.setTextDraft(event.textDraft());
            segment.setTextFinal(null);
            segment.setConfidence(null);
            segment.setStatus(TranscriptSegmentStatus.DRAFT);
        }
        segment.setSourceEventId(event.entryId());
        segment.setSourceSha256(event.sha256());
        segment.setSourceCorrelationId(event.correlationId());
        return TranscriptSegmentDto.from(segments.saveAndFlush(segment));
    }

    public static class SessionAssociationNotResolvedException extends IllegalStateException {
        public SessionAssociationNotResolvedException() {
            super("canonical session association is not resolved");
        }
    }

    public static class SessionAssociationConflictException extends IllegalStateException {
        public SessionAssociationConflictException() {
            super("canonical session association conflicts with the stored segment");
        }
    }

    public static class TranscriptAlreadyFinalizedException extends IllegalStateException {
        public TranscriptAlreadyFinalizedException() {
            super("new source chunks are rejected after transcript finalization");
        }
    }
}

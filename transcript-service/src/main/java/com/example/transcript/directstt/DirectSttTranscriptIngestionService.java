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
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Canonical, association-locked Direct-STT segment write path. */
@Service
public class DirectSttTranscriptIngestionService {

    private static final String SHA256_PREFIX = "sha256:";

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
                event.tenantId(), event.meetingId(), event.sourceSessionId(),
                event.transportEpoch(), event.windowSeq());
        TranscriptSessionAssociation association = associations.findSourceForUpdate(
                        event.tenantId(), event.meetingId(), DirectSttTranscriptResultEvent.SOURCE_SYSTEM,
                        event.sourceSessionId())
                .orElseThrow(() -> new SessionAssociationNotResolvedException());
        if (association.getStatus() != TranscriptSessionAssociationStatus.RESOLVED
                || association.getSessionId() == null
                || !association.getSessionId().equals(canonicalSessionId)) {
            throw new SessionAssociationNotResolvedException();
        }

        // windowSeq is unique only inside the gateway-owned transport epoch.
        // Both values restart across reconnects/transport legs, so neither the
        // counter nor the chunk range is a session-global identity.
        TranscriptSegment segment = segments.findDirectSttSourceTransportWindow(
                        event.tenantId(), event.meetingId(),
                        event.sourceSessionId(), event.transportEpoch(), event.windowSeq())
                .orElse(null);
        boolean legacyReplay = false;
        if (segment == null && event.transportEpoch() != 0) {
            TranscriptSegment legacy = segments.findDirectSttSourceTransportWindow(
                            event.tenantId(), event.meetingId(),
                            event.sourceSessionId(), 0L, event.windowSeq())
                    .orElse(null);
            if (legacy != null && sameSourceContent(legacy, event, false)) {
                segment = legacy;
                legacyReplay = true;
            }
        }
        if (segment != null) {
            if (!event.meetingId().equals(segment.getMeetingId())
                    || !canonicalSessionId.equals(segment.getSessionId())) {
                throw new SessionAssociationConflictException();
            }
            if (!sameSourceContent(segment, event, !legacyReplay)) {
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
            segment.setSourceTransportEpoch(event.transportEpoch());
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
            TranscriptSegment segment,
            DirectSttTranscriptResultEvent event,
            boolean requireTransportEpoch) {
        double startSeconds = event.chunkStartedAtMs() / 1000.0d;
        double endSeconds = startSeconds + durationSeconds(event);
        return DirectSttTranscriptResultEvent.SOURCE_SYSTEM.equals(segment.getSourceSystem())
                && event.tenantId().equals(segment.getTenantId())
                && event.sourceSessionId().equals(segment.getSourceSessionId())
                && (!requireTransportEpoch
                        || Objects.equals(segment.getSourceTransportEpoch(), event.transportEpoch()))
                && Objects.equals(segment.getSourceWindowSeq(), event.windowSeq())
                && Objects.equals(segment.getSourceFirstChunkSeq(), event.firstChunkSeq())
                && Objects.equals(segment.getSourceLastChunkSeq(), event.lastChunkSeq())
                && Objects.equals(segment.getSourceChunkSeq(), event.lastChunkSeq())
                && Objects.equals(segment.getStartTime(), startSeconds)
                && Objects.equals(segment.getEndTime(), endSeconds)
                && Objects.equals(segment.getTextDraft(), event.textDraft())
                && Objects.equals(
                        normalizeSha256(segment.getSourceSha256()), normalizeSha256(event.sha256()));
    }

    /**
     * Digest comparison must not depend on the producer's spelling.
     *
     * <p>Stored rows carry both {@code sha256:<hex>} and bare {@code <hex>}
     * (live evidence 2026-07-26: window 69 prefixed, window 76 bare). Comparing
     * the raw strings made every re-delivery of a prefixed row look like
     * different content, so an ordinary retry was classified as a replay
     * conflict and dropped.
     */
    static String normalizeSha256(String digest) {
        if (digest == null) {
            return null;
        }
        String trimmed = digest.trim();
        String bare = trimmed.regionMatches(true, 0, SHA256_PREFIX, 0, SHA256_PREFIX.length())
                ? trimmed.substring(SHA256_PREFIX.length())
                : trimmed;
        return bare.toLowerCase(Locale.ROOT);
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

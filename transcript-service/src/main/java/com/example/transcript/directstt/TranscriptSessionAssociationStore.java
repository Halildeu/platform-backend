package com.example.transcript.directstt;

import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Short transaction boundaries around association claim/fence operations. */
@Service
public class TranscriptSessionAssociationStore {

    private static final String SOURCE_SYSTEM = DirectSttTranscriptResultEvent.SOURCE_SYSTEM;

    private final TranscriptSessionAssociationRepository associations;
    private final TranscriptSegmentRepository segments;

    public TranscriptSessionAssociationStore(
            TranscriptSessionAssociationRepository associations,
            TranscriptSegmentRepository segments) {
        this.associations = associations;
        this.segments = segments;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public TranscriptSessionAssociation ensurePending(
            UUID id, UUID tenantId, UUID meetingId, String sourceSessionId, Instant now) {
        associations.insertPendingIfAbsent(
                id, tenantId, meetingId, SOURCE_SYSTEM, sourceSessionId, now);
        return require(tenantId, meetingId, sourceSessionId);
    }

    @Transactional(readOnly = true, propagation = Propagation.REQUIRES_NEW)
    public TranscriptSessionAssociation require(
            UUID tenantId, UUID meetingId, String sourceSessionId) {
        return associations.findByTenantIdAndMeetingIdAndSourceSystemAndSourceSessionId(
                        tenantId, meetingId, SOURCE_SYSTEM, sourceSessionId)
                .orElseThrow(() -> new IllegalStateException("session association row missing"));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(
            UUID tenantId,
            UUID meetingId,
            String sourceSessionId,
            UUID claimToken,
            Instant now,
            Instant leaseUntil) {
        return associations.claimResolution(
                tenantId, meetingId, SOURCE_SYSTEM, sourceSessionId,
                claimToken, now, leaseUntil) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean recoverStale(
            UUID tenantId,
            UUID meetingId,
            String sourceSessionId,
            int maxAttempts,
            Instant nextRetryAt,
            Instant now) {
        return associations.recoverStaleResolution(
                tenantId, meetingId, SOURCE_SYSTEM, sourceSessionId,
                maxAttempts, nextRetryAt, now) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean completeResolved(
            UUID associationId,
            UUID claimToken,
            UUID tenantId,
            UUID meetingId,
            String sourceSessionId,
            UUID sessionId,
            Instant now) {
        if (segments.countDirectSttSessionConflicts(
                tenantId, meetingId, sourceSessionId, sessionId) > 0) {
            throw new SessionAssociationConflictException();
        }
        int fenced = associations.markResolvedFenced(
                associationId, claimToken, sessionId, now);
        if (fenced == 1) {
            segments.backfillDirectSttSessionId(
                    tenantId, meetingId, sourceSessionId, sessionId);
            return true;
        }
        return false;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean fail(
            UUID associationId,
            UUID claimToken,
            String errorCode,
            int maxAttempts,
            boolean forceDead,
            Instant nextRetryAt,
            Instant now) {
        return associations.markResolutionFailedFenced(
                associationId, claimToken, errorCode, maxAttempts,
                forceDead, nextRetryAt, now) == 1;
    }

    @Transactional(readOnly = true)
    public Optional<TranscriptSessionAssociation> find(
            UUID tenantId, UUID meetingId, String sourceSessionId) {
        return associations.findByTenantIdAndMeetingIdAndSourceSystemAndSourceSessionId(
                tenantId, meetingId, SOURCE_SYSTEM, sourceSessionId);
    }

    public static class SessionAssociationConflictException extends IllegalStateException {
        public SessionAssociationConflictException() {
            super("canonical session association conflict");
        }
    }
}

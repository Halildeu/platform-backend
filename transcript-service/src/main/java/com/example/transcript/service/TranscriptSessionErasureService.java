package com.example.transcript.service;

import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSessionAssociation;
import com.example.transcript.model.TranscriptSessionErasureAudit;
import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.model.TranscriptSessionErasureTombstone;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionAssociationRepository;
import com.example.transcript.repository.TranscriptSessionErasureAuditRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.service.SessionErasureFence.UUIDScope;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Idempotent transcript-side erasure with permanent canonical/source fences. */
@Service
public class TranscriptSessionErasureService {

    private final TranscriptSessionErasureTombstoneRepository tombstones;
    private final TranscriptSessionErasureAuditRepository audits;
    private final TranscriptSessionAssociationRepository associations;
    private final TranscriptFinalizationRepository finalizations;
    private final TranscriptSegmentRepository segments;
    private final SessionErasureFence fence;
    private final TranscriptSessionErasureTombstoneStore tombstoneStore;
    private final Clock clock;

    @Autowired
    public TranscriptSessionErasureService(
            TranscriptSessionErasureTombstoneRepository tombstones,
            TranscriptSessionErasureAuditRepository audits,
            TranscriptSessionAssociationRepository associations,
            TranscriptFinalizationRepository finalizations,
            TranscriptSegmentRepository segments,
            SessionErasureFence fence,
            TranscriptSessionErasureTombstoneStore tombstoneStore) {
        this(tombstones, audits, associations, finalizations, segments,
                fence, tombstoneStore, Clock.systemUTC());
    }

    TranscriptSessionErasureService(
            TranscriptSessionErasureTombstoneRepository tombstones,
            TranscriptSessionErasureAuditRepository audits,
            TranscriptSessionAssociationRepository associations,
            TranscriptFinalizationRepository finalizations,
            TranscriptSegmentRepository segments,
            SessionErasureFence fence,
            TranscriptSessionErasureTombstoneStore tombstoneStore,
            Clock clock) {
        this.tombstones = tombstones;
        this.audits = audits;
        this.associations = associations;
        this.finalizations = finalizations;
        this.segments = segments;
        this.fence = fence;
        this.tombstoneStore = tombstoneStore;
        this.clock = clock;
    }

    @Transactional
    public Result erase(
            UUID tenantId, UUID meetingId, UUID sessionId, String requestedSourceSessionId) {
        String sourceSessionId = normalizeSource(requestedSourceSessionId);
        if (sourceSessionId == null) {
            sourceSessionId = associations.findByTenantIdAndMeetingIdAndSessionId(
                            tenantId, meetingId, sessionId).stream()
                    .map(TranscriptSessionAssociation::getSourceSessionId)
                    .filter(value -> value != null && !value.isBlank())
                    .sorted(Comparator.naturalOrder())
                    .findFirst()
                    .orElse(null);
        }
        final String resolvedSourceSessionId = sourceSessionId;
        UUIDScope scope = new UUIDScope(tenantId, meetingId, sessionId);
        tombstoneStore.observe(scope, resolvedSourceSessionId);
        fence.lock(
                SessionErasureFence.canonicalKey(scope),
                SessionErasureFence.sourceKey(tenantId, meetingId, resolvedSourceSessionId));

        TranscriptSessionErasureTombstone tombstone = tombstones.findSessionForUpdate(
                        tenantId, meetingId, sessionId)
                .orElseThrow(() -> new IllegalStateException("committed erasure tombstone is missing"));
        String requestedHash = SessionErasureFence.sourceHash(resolvedSourceSessionId);
        if (tombstone.getSourceSessionHash() != null
                && requestedHash != null
                && !tombstone.getSourceSessionHash().equals(requestedHash)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "ERASURE_SOURCE_SCOPE_MISMATCH");
        }
        if (tombstone.getStatus() == TranscriptSessionErasureStatus.COMPLETE) {
            return result(tombstone);
        }

        associations.findCanonicalForUpdate(tenantId, meetingId, sessionId);
        if (resolvedSourceSessionId != null) {
            associations.findSourceForUpdate(
                    tenantId, meetingId, "DIRECT_STT", resolvedSourceSessionId);
        }
        List<TranscriptFinalization> heldScope = finalizations.findErasureScopeForUpdate(
                tenantId, meetingId, sessionId);
        if (heldScope.stream().anyMatch(TranscriptFinalization::isLegalHold)) {
            return markHeld(tombstone);
        }

        int finalizationDeleted = finalizations.deleteErasureScope(tenantId, meetingId, sessionId);
        // Delete-time predicate is authoritative if legal_hold changed after selection.
        if (finalizations.existsLegalHoldForErasure(tenantId, meetingId, sessionId)) {
            return markHeld(tombstone);
        }
        int segmentDeleted = segments.deleteCanonicalErasureScope(tenantId, meetingId, sessionId);
        if (resolvedSourceSessionId != null) {
            segmentDeleted += segments.deleteLegacySourceErasureScope(
                    tenantId, meetingId, resolvedSourceSessionId);
        }
        int associationDeleted = associations.deleteErasureScope(
                tenantId, meetingId, sessionId, resolvedSourceSessionId);

        Instant now = clock.instant();
        tombstone.setStatus(TranscriptSessionErasureStatus.COMPLETE);
        tombstone.setSegmentDeletedCount(segmentDeleted);
        tombstone.setFinalizationDeletedCount(finalizationDeleted);
        tombstone.setAssociationDeletedCount(associationDeleted);
        tombstone.setCompletedAt(now);
        tombstone.setUpdatedAt(now);
        tombstones.saveAndFlush(tombstone);
        audit(tombstone, now);
        return result(tombstone);
    }

    private Result markHeld(TranscriptSessionErasureTombstone tombstone) {
        Instant now = clock.instant();
        tombstone.setStatus(TranscriptSessionErasureStatus.HELD);
        tombstone.setCompletedAt(null);
        tombstone.setUpdatedAt(now);
        tombstones.saveAndFlush(tombstone);
        audit(tombstone, now);
        return result(tombstone);
    }

    private void audit(TranscriptSessionErasureTombstone tombstone, Instant now) {
        TranscriptSessionErasureAudit audit = new TranscriptSessionErasureAudit();
        audit.setId(UUID.randomUUID());
        audit.setTombstoneId(tombstone.getId());
        audit.setState(tombstone.getStatus());
        audit.setSegmentDeletedCount(tombstone.getSegmentDeletedCount());
        audit.setFinalizationDeletedCount(tombstone.getFinalizationDeletedCount());
        audit.setAssociationDeletedCount(tombstone.getAssociationDeletedCount());
        audit.setExecutedAt(now);
        audits.save(audit);
    }

    private static Result result(TranscriptSessionErasureTombstone row) {
        int deleted = row.getSegmentDeletedCount()
                + row.getFinalizationDeletedCount()
                + row.getAssociationDeletedCount();
        return new Result(
                row.getTenantId(), row.getMeetingId(), row.getSessionId(),
                row.getStatus(), deleted);
    }

    private static String normalizeSource(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        if (value.length() > 128) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SOURCE_SESSION_ID_INVALID");
        }
        return value;
    }

    public record Result(
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            TranscriptSessionErasureStatus status,
            int deletedCount) {
    }
}

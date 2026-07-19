package com.example.transcript.service;

import com.example.transcript.dto.CanonicalTranscriptSegmentDto;
import com.example.transcript.dto.CanonicalTranscriptSnapshotDto;
import com.example.transcript.finalization.FinalizedTranscriptSnapshotCodec;
import com.example.transcript.finalization.TranscriptSnapshotHasher;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSessionErasureStatus;
import com.example.transcript.model.TranscriptSessionErasureTombstone;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSessionErasureTombstoneRepository;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.AnalysisJobCapabilityIssuer;
import com.example.transcript.security.AnalysisSpecVersionPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Reads and integrity-checks one immutable finalized transcript occurrence. */
@Service
public class CanonicalTranscriptReadService {

    private final TranscriptFinalizationRepository finalizationRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final FinalizedTranscriptSnapshotCodec snapshotCodec;
    private final AnalysisJobCapabilityIssuer capabilityIssuer;
    private final AnalysisSpecVersionPolicy analysisSpecVersionPolicy;
    private final TranscriptAccessAuditService accessAuditService;
    private final TranscriptSessionErasureTombstoneRepository erasureTombstones;
    private final SessionErasureFence erasureFence;

    public CanonicalTranscriptReadService(
            TranscriptFinalizationRepository finalizationRepository,
            TranscriptSegmentRepository segmentRepository,
            FinalizedTranscriptSnapshotCodec snapshotCodec,
            AnalysisJobCapabilityIssuer capabilityIssuer,
            AnalysisSpecVersionPolicy analysisSpecVersionPolicy,
            TranscriptAccessAuditService accessAuditService,
            TranscriptSessionErasureTombstoneRepository erasureTombstones,
            SessionErasureFence erasureFence) {
        this.finalizationRepository = finalizationRepository;
        this.segmentRepository = segmentRepository;
        this.snapshotCodec = snapshotCodec;
        this.capabilityIssuer = capabilityIssuer;
        this.analysisSpecVersionPolicy = analysisSpecVersionPolicy;
        this.accessAuditService = accessAuditService;
        this.erasureTombstones = erasureTombstones;
        this.erasureFence = erasureFence;
    }

    @Transactional
    public CanonicalTranscriptSnapshotDto read(
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            long finalizationVersion,
            UUID requestedTenantId,
            UUID analysisRunId,
            String analysisSpecVersion,
            String serviceSubject) {
        if (finalizationVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FINALIZATION_VERSION_INVALID");
        }
        if (!tenantId.equals(requestedTenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_SCOPE_MISMATCH");
        }
        analysisSpecVersionPolicy.requireAllowed(analysisSpecVersion);
        SessionErasureFence.UUIDScope scope =
                new SessionErasureFence.UUIDScope(tenantId, meetingId, sessionId);
        erasureFence.lock(SessionErasureFence.canonicalKey(scope));
        Optional<TranscriptSessionErasureTombstone> tombstone = erasureTombstones
                .findByTenantIdAndMeetingIdAndSessionId(tenantId, meetingId, sessionId);
        if (tombstone.map(TranscriptSessionErasureTombstone::getStatus)
                .filter(TranscriptSessionErasureStatus.COMPLETE::equals).isPresent()) {
            throw new ResponseStatusException(HttpStatus.GONE, "TRANSCRIPT_ERASED");
        }
        TranscriptFinalization finalization = finalizationRepository
                .findVisibleAnalysisOccurrence(
                        tenantId, meetingId, sessionId, finalizationVersion, analysisRunId)
                .orElseThrow(() -> tombstone.isPresent()
                        ? new ResponseStatusException(HttpStatus.LOCKED, "TRANSCRIPT_ERASURE_PENDING")
                        : new ResponseStatusException(HttpStatus.NOT_FOUND, "FINALIZATION_NOT_FOUND"));
        if (tombstone.isPresent() && !finalization.isLegalHold()) {
            throw new ResponseStatusException(HttpStatus.LOCKED, "TRANSCRIPT_ERASURE_PENDING");
        }
        FinalizedTranscriptSnapshotCodec.StoredSnapshot storedSnapshot = restoreChecked(
                finalization, tenantId, meetingId, sessionId);

        List<CanonicalTranscriptSegmentDto> responseSegments = storedSnapshot.segments().stream()
                .map(segment -> new CanonicalTranscriptSegmentDto(
                        segment.text(), segment.start(), segment.end()))
                .toList();
        CanonicalTranscriptSnapshotDto snapshot = new CanonicalTranscriptSnapshotDto(
                tenantId,
                meetingId,
                sessionId,
                finalizationVersion,
                finalization.getFinalizedAt(),
                finalization.isLegalHold() ? "LEGAL_HOLD" : "FINALIZED",
                storedSnapshot.transcript(),
                storedSnapshot.transcriptSha256(),
                responseSegments.size(),
                responseSegments);
        accessAuditService.recordList(
                new AdminTenantContext(tenantId, serviceSubject), meetingId, sessionId,
                storedSnapshot.segmentCount());
        return snapshot;
    }

    @Transactional(readOnly = true)
    public AnalysisJobCapabilityIssuer.IssuedCapability issueAnalysisCapability(
            UUID tenantId,
            UUID meetingId,
            UUID sessionId,
            long finalizationVersion,
            UUID requestedTenantId,
            UUID analysisRunId,
            String analysisSpecVersion) {
        if (finalizationVersion < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "FINALIZATION_VERSION_INVALID");
        }
        if (!tenantId.equals(requestedTenantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "TENANT_SCOPE_MISMATCH");
        }
        analysisSpecVersionPolicy.requireAllowed(analysisSpecVersion);
        SessionErasureFence.UUIDScope scope =
                new SessionErasureFence.UUIDScope(tenantId, meetingId, sessionId);
        erasureFence.lock(SessionErasureFence.canonicalKey(scope));
        Optional<TranscriptSessionErasureTombstone> tombstone = erasureTombstones
                .findByTenantIdAndMeetingIdAndSessionId(tenantId, meetingId, sessionId);
        if (tombstone.isPresent()) {
            HttpStatus status = tombstone.get().getStatus() == TranscriptSessionErasureStatus.COMPLETE
                    ? HttpStatus.GONE : HttpStatus.LOCKED;
            String code = status == HttpStatus.GONE
                    ? "TRANSCRIPT_ERASED" : "TRANSCRIPT_ERASURE_PENDING";
            throw new ResponseStatusException(status, code);
        }
        TranscriptFinalization finalization = finalizationRepository
                .findVisibleAnalysisOccurrence(
                        tenantId, meetingId, sessionId, finalizationVersion, analysisRunId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FINALIZATION_NOT_FOUND"));
        FinalizedTranscriptSnapshotCodec.StoredSnapshot storedSnapshot = restoreChecked(
                finalization, tenantId, meetingId, sessionId);
        return capabilityIssuer.issue(
                new AnalysisJobCapabilityIssuer.JobBinding(
                        tenantId,
                        meetingId,
                        sessionId,
                        finalizationVersion,
                        finalization.getFinalizedAt(),
                        storedSnapshot.transcriptSha256(),
                        analysisRunId,
                        analysisSpecVersion));
    }

    private FinalizedTranscriptSnapshotCodec.StoredSnapshot restoreChecked(
            TranscriptFinalization finalization,
            UUID tenantId,
            UUID meetingId,
            UUID sessionId) {
        try {
            return snapshotCodec.hasPersistedProjection(finalization)
                    ? snapshotCodec.restore(finalization)
                    : restoreLegacy(finalization, tenantId, meetingId, sessionId);
        } catch (FinalizedTranscriptSnapshotCodec.InvalidStoredSnapshotException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FINALIZATION_INTEGRITY_MISMATCH");
        }
    }

    private FinalizedTranscriptSnapshotCodec.StoredSnapshot restoreLegacy(
            TranscriptFinalization finalization,
            UUID tenantId,
            UUID meetingId,
            UUID sessionId) {
        List<TranscriptSegment> segments = segmentRepository.findCanonicalFinalizedSession(
                tenantId, meetingId, sessionId);
        if (segments.size() != finalization.getSegmentCount()) {
            throw new FinalizedTranscriptSnapshotCodec.InvalidStoredSnapshotException(
                    "LEGACY_SEGMENT_COUNT_MISMATCH");
        }

        FinalizedTranscriptSnapshotCodec.StoredSnapshot editorial = captureEditorial(segments);
        if (editorial != null && sourceHashMatches(finalization, editorial)) {
            return editorial;
        }
        FinalizedTranscriptSnapshotCodec.StoredSnapshot machine = captureMachine(segments);
        if (machine != null && sourceHashMatches(finalization, machine)) {
            return machine;
        }
        throw new FinalizedTranscriptSnapshotCodec.InvalidStoredSnapshotException(
                "LEGACY_SOURCE_HASH_MISMATCH");
    }

    private FinalizedTranscriptSnapshotCodec.StoredSnapshot captureEditorial(
            List<TranscriptSegment> segments) {
        try {
            return snapshotCodec.captureEditorial(segments);
        } catch (TranscriptSnapshotHasher.InvalidSnapshotException ex) {
            return null;
        }
    }

    private FinalizedTranscriptSnapshotCodec.StoredSnapshot captureMachine(
            List<TranscriptSegment> segments) {
        try {
            return snapshotCodec.captureMachine(segments);
        } catch (TranscriptSnapshotHasher.InvalidSnapshotException ex) {
            return null;
        }
    }

    private boolean sourceHashMatches(
            TranscriptFinalization finalization,
            FinalizedTranscriptSnapshotCodec.StoredSnapshot snapshot) {
        return MessageDigest.isEqual(
                finalization.getSnapshotSha256().getBytes(StandardCharsets.US_ASCII),
                snapshot.sourceSnapshotSha256().getBytes(StandardCharsets.US_ASCII));
    }

}

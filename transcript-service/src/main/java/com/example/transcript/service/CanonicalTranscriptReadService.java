package com.example.transcript.service;

import com.example.transcript.dto.CanonicalTranscriptSegmentDto;
import com.example.transcript.dto.CanonicalTranscriptSnapshotDto;
import com.example.transcript.finalization.FinalizedTranscriptSnapshotCodec;
import com.example.transcript.finalization.TranscriptSnapshotHasher;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.security.AdminTenantContext;
import com.example.transcript.security.AnalysisJobCapabilityIssuer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/** Reads and integrity-checks one immutable finalized transcript occurrence. */
@Service
public class CanonicalTranscriptReadService {

    private final TranscriptFinalizationRepository finalizationRepository;
    private final TranscriptSegmentRepository segmentRepository;
    private final FinalizedTranscriptSnapshotCodec snapshotCodec;
    private final AnalysisJobCapabilityIssuer capabilityIssuer;
    private final TranscriptAccessAuditService accessAuditService;

    public CanonicalTranscriptReadService(
            TranscriptFinalizationRepository finalizationRepository,
            TranscriptSegmentRepository segmentRepository,
            FinalizedTranscriptSnapshotCodec snapshotCodec,
            AnalysisJobCapabilityIssuer capabilityIssuer,
            TranscriptAccessAuditService accessAuditService) {
        this.finalizationRepository = finalizationRepository;
        this.segmentRepository = segmentRepository;
        this.snapshotCodec = snapshotCodec;
        this.capabilityIssuer = capabilityIssuer;
        this.accessAuditService = accessAuditService;
    }

    @Transactional
    public CanonicalReadResult read(
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
        if (!StringUtils.hasText(analysisSpecVersion) || analysisSpecVersion.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "ANALYSIS_SPEC_VERSION_INVALID");
        }
        TranscriptFinalization finalization = finalizationRepository
                .findVisibleOccurrence(tenantId, meetingId, sessionId, finalizationVersion)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "FINALIZATION_NOT_FOUND"));
        FinalizedTranscriptSnapshotCodec.StoredSnapshot storedSnapshot;
        try {
            storedSnapshot = snapshotCodec.hasPersistedProjection(finalization)
                    ? snapshotCodec.restore(finalization)
                    : restoreLegacy(finalization, tenantId, meetingId, sessionId);
        } catch (FinalizedTranscriptSnapshotCodec.InvalidStoredSnapshotException ex) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "FINALIZATION_INTEGRITY_MISMATCH");
        }

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
                "FINALIZED",
                storedSnapshot.transcript(),
                storedSnapshot.transcriptSha256(),
                responseSegments.size(),
                responseSegments);
        AnalysisJobCapabilityIssuer.IssuedCapability capability = capabilityIssuer.issue(
                new AnalysisJobCapabilityIssuer.JobBinding(
                        tenantId,
                        meetingId,
                        sessionId,
                        finalizationVersion,
                        finalization.getFinalizedAt(),
                        storedSnapshot.transcriptSha256(),
                        analysisRunId,
                        analysisSpecVersion));
        accessAuditService.recordList(
                new AdminTenantContext(tenantId, serviceSubject), meetingId, sessionId,
                storedSnapshot.segmentCount());
        return new CanonicalReadResult(snapshot, capability.token(), capability.expiresAt());
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

    public record CanonicalReadResult(
            CanonicalTranscriptSnapshotDto snapshot,
            String capability,
            java.time.Instant capabilityExpiresAt) { }
}

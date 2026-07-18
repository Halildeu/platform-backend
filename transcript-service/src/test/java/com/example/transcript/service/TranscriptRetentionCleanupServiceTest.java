package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.transcript.model.TranscriptAccessAudit;
import com.example.transcript.model.TranscriptAccessType;
import com.example.transcript.model.TranscriptFinalization;
import com.example.transcript.model.TranscriptRetentionDestructionAudit;
import com.example.transcript.model.TranscriptSegment;
import com.example.transcript.model.TranscriptSegmentStatus;
import com.example.transcript.repository.TranscriptAccessAuditRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptRetentionDestructionAuditRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
import com.example.transcript.repository.TranscriptSourceRetentionFenceRepository;
import com.example.transcript.testsupport.IsolatedH2DataJpaTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@IsolatedH2DataJpaTest
@Import({TranscriptRetentionCleanupService.class, SourceWindowRetentionFence.class})
@TestPropertySource(properties = {
        "transcript.retention.cleanup-batch-size=1",
        "transcript.retention.cleanup-max-batches-per-run=1"
})
class TranscriptRetentionCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z");
    private static final Instant EXPIRED_TRANSCRIPT = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant EXPIRED_ACCESS = Instant.parse("2023-01-01T00:00:00Z");
    private static final Instant FRESH = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private TranscriptRetentionCleanupService service;
    @Autowired
    private TranscriptSegmentRepository segmentRepository;
    @Autowired
    private TranscriptFinalizationRepository finalizationRepository;
    @Autowired
    private TranscriptAccessAuditRepository accessAuditRepository;
    @Autowired
    private TranscriptRetentionDestructionAuditRepository destructionAuditRepository;
    @Autowired
    private TranscriptSourceRetentionFenceRepository sourceRetentionFenceRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void cleanupDeletesExpiredTranscriptAndAccessRowsWithMetadataOnlyAudit() {
        TranscriptSegment expiredSegment = segment(EXPIRED_TRANSCRIPT, "raw transcript must not be audited");
        TranscriptSegment freshSegment = segment(FRESH, "fresh transcript remains");
        TranscriptAccessAudit expiredAccess = accessAudit(EXPIRED_ACCESS);
        TranscriptAccessAudit freshAccess = accessAudit(FRESH);

        TranscriptRetentionCleanupService.CleanupResult result = service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(segmentRepository.findById(expiredSegment.getId())).isEmpty();
        assertThat(accessAuditRepository.findById(expiredAccess.getId())).isEmpty();
        assertThat(segmentRepository.findById(freshSegment.getId())).isPresent();
        assertThat(accessAuditRepository.findById(freshAccess.getId())).isPresent();
        assertThat(sourceRetentionFenceRepository
                .existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceWindowSeq(
                        expiredSegment.getTenantId(), expiredSegment.getMeetingId(),
                        SessionErasureFence.sourceHash(expiredSegment.getSourceSessionId()), 1L))
                .isTrue();
        assertThat(sourceRetentionFenceRepository
                .existsByTenantIdAndMeetingIdAndSourceSessionHashAndSourceWindowSeq(
                        freshSegment.getTenantId(), freshSegment.getMeetingId(),
                        SessionErasureFence.sourceHash(freshSegment.getSourceSessionId()), 1L))
                .isFalse();

        List<TranscriptRetentionDestructionAudit> transcriptAudits = destructionAuditRepository
                .findByLayerIdOrderByExecutedAtDesc(TranscriptRetentionCleanupService.LAYER_TRANSCRIPT_RECORDS);
        List<TranscriptRetentionDestructionAudit> accessAudits = destructionAuditRepository
                .findByLayerIdOrderByExecutedAtDesc(TranscriptRetentionCleanupService.LAYER_KVKK_ACCESS_LOG);
        assertThat(transcriptAudits).hasSize(1);
        assertThat(accessAudits).hasSize(1);
        assertThat(transcriptAudits.get(0).getDeletedCount()).isEqualTo(1);
        assertThat(accessAudits.get(0).getDeletedCount()).isEqualTo(1);
        assertThat(transcriptAudits.get(0).getAuditPayload()).isEqualTo("metadata-only");
        assertThat(accessAudits.get(0).getAuditPayload()).isEqualTo("metadata-only");
        assertThat(transcriptAudits.get(0).getJobId())
                .isEqualTo(TranscriptRetentionCleanupService.JOB_TRANSCRIPT_RECORDS);
        assertThat(accessAudits.get(0).getJobId())
                .isEqualTo(TranscriptRetentionCleanupService.JOB_KVKK_ACCESS_LOG);
    }

    @Test
    void cleanupStopsAtConfiguredBatchLimitAndAuditsActualDeletes() {
        TranscriptSegment firstExpired = segment(EXPIRED_TRANSCRIPT, "first expired transcript");
        TranscriptSegment secondExpired = segment(EXPIRED_TRANSCRIPT.plusSeconds(60), "second expired transcript");

        TranscriptRetentionCleanupService.CleanupResult result = service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.transcriptRecords().deletedCount()).isEqualTo(1);
        assertThat(segmentRepository.findById(firstExpired.getId())).isEmpty();
        assertThat(segmentRepository.findById(secondExpired.getId())).isPresent();

        List<TranscriptRetentionDestructionAudit> transcriptAudits = destructionAuditRepository
                .findByLayerIdOrderByExecutedAtDesc(TranscriptRetentionCleanupService.LAYER_TRANSCRIPT_RECORDS);
        assertThat(transcriptAudits).hasSize(1);
        assertThat(transcriptAudits.get(0).getDeletedCount()).isEqualTo(1);
        assertThat(transcriptAudits.get(0).getAuditPayload()).isEqualTo("metadata-only");
    }

    @Test
    void cleanupDeletesExpiredFinalizedSnapshotButPreservesLegalHold() {
        TranscriptSegment expired = segment(EXPIRED_TRANSCRIPT, "expired canonical transcript");
        TranscriptFinalization expiredFinalization = finalization(expired, false, EXPIRED_TRANSCRIPT);
        TranscriptSegment held = segment(EXPIRED_TRANSCRIPT.plusSeconds(1), "held canonical transcript");
        TranscriptFinalization heldFinalization = finalization(held, true, EXPIRED_TRANSCRIPT.plusSeconds(1));

        TranscriptRetentionCleanupService.CleanupResult result = service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.transcriptRecords().deletedCount()).isEqualTo(2);
        assertThat(segmentRepository.findById(expired.getId())).isEmpty();
        assertThat(finalizationRepository.findById(expiredFinalization.getId())).isEmpty();
        assertThat(segmentRepository.findById(held.getId())).isPresent();
        assertThat(finalizationRepository.findById(heldFinalization.getId())).isPresent();

        TranscriptRetentionDestructionAudit audit = destructionAuditRepository
                .findByLayerIdOrderByExecutedAtDesc(TranscriptRetentionCleanupService.LAYER_TRANSCRIPT_RECORDS)
                .get(0);
        assertThat(audit.getDeletedCount()).isEqualTo(2);
        assertThat(audit.getAuditPayload()).isEqualTo("metadata-only");
    }

    @Test
    void deletePredicatesRecheckLegalHoldAfterExpiredIdSelection() {
        TranscriptSegment segment = segment(EXPIRED_TRANSCRIPT, "candidate");
        TranscriptFinalization finalization = finalization(segment, false, EXPIRED_TRANSCRIPT);
        List<UUID> segmentIds = segmentRepository.findExpiredIds(
                NOW, org.springframework.data.domain.PageRequest.of(0, 10));
        List<UUID> finalizationIds = finalizationRepository.findExpiredIds(
                NOW, org.springframework.data.domain.PageRequest.of(0, 10));
        finalization.setLegalHold(true);
        finalizationRepository.saveAndFlush(finalization);

        assertThat(segmentRepository.deleteByIdIn(segmentIds)).isZero();
        assertThat(finalizationRepository.deleteByIdIn(finalizationIds)).isZero();
        assertThat(segmentRepository.findById(segment.getId())).isPresent();
        assertThat(finalizationRepository.findById(finalization.getId())).isPresent();
    }

    private TranscriptSegment segment(Instant createdAt, String draft) {
        UUID org = UUID.randomUUID();
        TranscriptSegment segment = new TranscriptSegment();
        segment.setTenantId(org);
        segment.setOrgId(org);
        segment.setMeetingId(UUID.randomUUID());
        segment.setSessionId(UUID.randomUUID());
        segment.setSourceSystem("DIRECT_STT");
        segment.setSourceSessionId("SES-" + UUID.randomUUID());
        segment.setSourceWindowSeq(1L);
        segment.setSourceFirstChunkSeq(1L);
        segment.setSourceLastChunkSeq(1L);
        segment.setSourceChunkSeq(1L);
        segment.setStartTime(0.0);
        segment.setEndTime(1.0);
        segment.setTextDraft(draft);
        segment.setStatus(TranscriptSegmentStatus.DRAFT);
        TranscriptSegment saved = segmentRepository.saveAndFlush(segment);
        setSegmentTimestamps(saved.getId(), createdAt);
        return saved;
    }

    private TranscriptAccessAudit accessAudit(Instant accessedAt) {
        UUID org = UUID.randomUUID();
        TranscriptAccessAudit audit = new TranscriptAccessAudit();
        audit.setTenantId(org);
        audit.setOrgId(org);
        audit.setAccessorSubject("subject-redacted");
        audit.setMeetingId(UUID.randomUUID());
        audit.setAccessType(TranscriptAccessType.LIST);
        audit.setAccessedAt(accessedAt);
        audit.setResultCount(1);
        return accessAuditRepository.saveAndFlush(audit);
    }

    private TranscriptFinalization finalization(
            TranscriptSegment segment, boolean legalHold, Instant createdAt) {
        TranscriptFinalization finalization = new TranscriptFinalization();
        finalization.setId(UUID.randomUUID());
        finalization.setTenantId(segment.getTenantId());
        finalization.setOrgId(segment.getOrgId());
        finalization.setMeetingId(segment.getMeetingId());
        finalization.setSessionId(segment.getSessionId());
        finalization.setFinalizationVersion(1L);
        finalization.setSegmentCount(1);
        finalization.setSnapshotSha256("a".repeat(64));
        finalization.setFinalizedAt(createdAt);
        finalization.setCreatedAt(createdAt);
        finalization.setLegalHold(legalHold);
        return finalizationRepository.saveAndFlush(finalization);
    }

    private void setSegmentTimestamps(UUID id, Instant timestamp) {
        entityManager.createNativeQuery("""
                        update public.transcript_segments
                        set created_at = ?, updated_at = ?
                        where id = ?
                        """)
                .setParameter(1, Timestamp.from(timestamp))
                .setParameter(2, Timestamp.from(timestamp))
                .setParameter(3, id)
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
    }
}

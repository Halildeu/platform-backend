package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunDestructionReason;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingIntelligenceResultAccessAudit;
import com.example.meeting.model.MeetingIntelligenceResultAccessType;
import com.example.meeting.model.MeetingItemSource;
import com.example.meeting.model.MeetingRetentionDestructionAudit;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingAnalysisRunDestructionTombstoneRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingIntelligenceResultAccessAuditRepository;
import com.example.meeting.repository.MeetingRetentionDestructionAuditRepository;
import com.example.meeting.testsupport.IsolatedH2DataJpaTest;
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
@Import({MeetingRetentionCleanupService.class, MeetingAnalysisRunDestructionRecorder.class})
@TestPropertySource(properties = {
        "meeting.retention.cleanup-batch-size=1",
        "meeting.retention.cleanup-max-batches-per-run=1"
})
class MeetingRetentionCleanupServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-25T12:00:00Z");
    private static final Instant EXPIRED = Instant.parse("2024-01-01T00:00:00Z");
    private static final Instant FRESH = Instant.parse("2026-01-01T00:00:00Z");

    @Autowired
    private MeetingRetentionCleanupService service;
    @Autowired
    private MeetingActionRepository actionRepository;
    @Autowired
    private MeetingAnalysisRunRepository analysisRunRepository;
    @Autowired
    private MeetingAnalysisRunDestructionTombstoneRepository destructionTombstoneRepository;
    @Autowired
    private MeetingDecisionRepository decisionRepository;
    @Autowired
    private MeetingIntelligenceResultAccessAuditRepository resultAccessAuditRepository;
    @Autowired
    private MeetingRetentionDestructionAuditRepository auditRepository;
    @Autowired
    private EntityManager entityManager;

    @Test
    void cleanupDeletesExpiredMeetingIntelligenceRowsAndWritesMetadataOnlyAudit() {
        MeetingAction expiredAction = action(EXPIRED, "raw action text must not be audited");
        MeetingAction freshAction = action(FRESH, "fresh action remains");
        MeetingDecision expiredDecision = decision(EXPIRED, "raw decision title");
        MeetingDecision freshDecision = decision(FRESH, "fresh decision remains");
        MeetingIntelligenceResultAccessAudit expiredAccessAudit = accessAudit(EXPIRED);
        MeetingIntelligenceResultAccessAudit freshAccessAudit = accessAudit(FRESH);

        MeetingRetentionCleanupService.CleanupResult result = service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.deletedCount()).isEqualTo(3);
        assertThat(actionRepository.findById(expiredAction.getId())).isEmpty();
        assertThat(decisionRepository.findById(expiredDecision.getId())).isEmpty();
        assertThat(actionRepository.findById(freshAction.getId())).isPresent();
        assertThat(decisionRepository.findById(freshDecision.getId())).isPresent();
        assertThat(resultAccessAuditRepository.findById(expiredAccessAudit.getId())).isEmpty();
        assertThat(resultAccessAuditRepository.findById(freshAccessAudit.getId())).isPresent();

        List<MeetingRetentionDestructionAudit> audits = auditRepository
                .findByLayerIdOrderByExecutedAtDesc(MeetingRetentionCleanupService.LAYER_MEETING_INTELLIGENCE);
        assertThat(audits).hasSize(1);
        MeetingRetentionDestructionAudit audit = audits.get(0);
        assertThat(audit.getDeletedCount()).isEqualTo(2);
        assertThat(audit.getActionDeletedCount()).isEqualTo(1);
        assertThat(audit.getDecisionDeletedCount()).isEqualTo(1);
        assertThat(audit.getResultAccessAuditDeletedCount()).isZero();
        assertThat(audit.getAuditPayload()).isEqualTo("metadata-only");
        assertThat(audit.getJobId()).isEqualTo(MeetingRetentionCleanupService.JOB_ID);
        assertThat(audit.getLayerId()).isEqualTo("db.meeting-intelligence");

        List<MeetingRetentionDestructionAudit> accessAudits = auditRepository
                .findByLayerIdOrderByExecutedAtDesc(
                        MeetingRetentionCleanupService.LAYER_RESULT_ACCESS_AUDIT);
        assertThat(accessAudits).hasSize(1);
        MeetingRetentionDestructionAudit accessAudit = accessAudits.get(0);
        assertThat(accessAudit.getDeletedCount()).isEqualTo(1);
        assertThat(accessAudit.getActionDeletedCount()).isZero();
        assertThat(accessAudit.getDecisionDeletedCount()).isZero();
        assertThat(accessAudit.getResultAccessAuditDeletedCount()).isEqualTo(1);
        assertThat(accessAudit.getJobId())
                .isEqualTo(MeetingRetentionCleanupService.RESULT_ACCESS_AUDIT_JOB_ID);
    }

    @Test
    void cleanupStopsAtConfiguredBatchLimitAndAuditsActualDeletes() {
        MeetingAction firstExpired = action(EXPIRED, "first expired action");
        MeetingAction secondExpired = action(EXPIRED.plusSeconds(60), "second expired action");

        MeetingRetentionCleanupService.CleanupResult result = service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.actionDeletedCount()).isEqualTo(1);
        assertThat(result.decisionDeletedCount()).isZero();
        assertThat(actionRepository.findById(firstExpired.getId())).isEmpty();
        assertThat(actionRepository.findById(secondExpired.getId())).isPresent();

        List<MeetingRetentionDestructionAudit> audits = auditRepository
                .findByLayerIdOrderByExecutedAtDesc(MeetingRetentionCleanupService.LAYER_MEETING_INTELLIGENCE);
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).getDeletedCount()).isEqualTo(1);
        assertThat(audits.get(0).getActionDeletedCount()).isEqualTo(1);
        assertThat(audits.get(0).getDecisionDeletedCount()).isZero();
        assertThat(audits.get(0).getResultAccessAuditDeletedCount()).isZero();
    }

    @Test
    void cleanupDeletesExpiredAnalysisRunButPreservesLegalHoldAndFreshRows() {
        MeetingAnalysisRun expired = analysisRun(EXPIRED, false);
        MeetingAnalysisRun held = analysisRun(EXPIRED.plusSeconds(1), true);
        MeetingAnalysisRun fresh = analysisRun(FRESH, false);

        MeetingRetentionCleanupService.CleanupResult result = service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.analysisRunDeletedCount()).isEqualTo(1);
        assertThat(analysisRunRepository.findById(expired.getAnalysisRunId())).isEmpty();
        assertThat(destructionTombstoneRepository.findById(expired.getAnalysisRunId()))
                .get()
                .extracting(tombstone -> tombstone.getReason())
                .isEqualTo(MeetingAnalysisRunDestructionReason.RETENTION);
        assertThat(analysisRunRepository.findById(held.getAnalysisRunId())).isPresent();
        assertThat(analysisRunRepository.findById(fresh.getAnalysisRunId())).isPresent();

        MeetingRetentionDestructionAudit audit = auditRepository
                .findByLayerIdOrderByExecutedAtDesc(MeetingRetentionCleanupService.LAYER_MEETING_INTELLIGENCE)
                .get(0);
        assertThat(audit.getAnalysisRunDeletedCount()).isEqualTo(1);
        assertThat(audit.getAuditPayload()).isEqualTo("metadata-only");
    }

    @Test
    void heldParentPreservesAiChildrenWhileExpiredManualChildrenRemainIndependent() {
        MeetingAnalysisRun held = analysisRun(EXPIRED, true);
        MeetingAction aiAction = action(EXPIRED, "held AI action");
        aiAction.setSource(MeetingItemSource.AI_ANALYSIS);
        aiAction.setAnalysisRunId(held.getAnalysisRunId());
        aiAction.setOrdinal(0);
        actionRepository.saveAndFlush(aiAction);
        MeetingDecision aiDecision = decision(EXPIRED, "held AI decision");
        aiDecision.setSource(MeetingItemSource.AI_ANALYSIS);
        aiDecision.setAnalysisRunId(held.getAnalysisRunId());
        aiDecision.setOrdinal(0);
        decisionRepository.saveAndFlush(aiDecision);

        service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(analysisRunRepository.findById(held.getAnalysisRunId())).isPresent();
        assertThat(actionRepository.findById(aiAction.getId())).isPresent();
        assertThat(decisionRepository.findById(aiDecision.getId())).isPresent();
    }

    @Test
    void deletePredicateRechecksLegalHoldAfterExpiredIdSelection() {
        MeetingAnalysisRun candidate = analysisRun(EXPIRED, false);
        List<UUID> ids = analysisRunRepository.findExpiredIds(
                NOW, org.springframework.data.domain.PageRequest.of(0, 10));
        candidate = analysisRunRepository.findById(candidate.getAnalysisRunId()).orElseThrow();
        candidate.setLegalHold(true);
        analysisRunRepository.saveAndFlush(candidate);

        assertThat(analysisRunRepository.deleteByIdIn(ids)).isZero();
        assertThat(analysisRunRepository.findById(candidate.getAnalysisRunId())).isPresent();
    }

    private MeetingIntelligenceResultAccessAudit accessAudit(Instant accessedAt) {
        UUID org = UUID.randomUUID();
        MeetingIntelligenceResultAccessAudit audit = new MeetingIntelligenceResultAccessAudit();
        audit.setTenantId(org);
        audit.setOrgId(org);
        audit.setAccessorSubject("reader");
        audit.setMeetingId(UUID.randomUUID());
        audit.setAnalysisRunId(UUID.randomUUID());
        audit.setAccessType(MeetingIntelligenceResultAccessType.CANONICAL_RESULT_READ);
        audit.setResultCount(1);
        audit.setAccessedAt(accessedAt);
        return resultAccessAuditRepository.saveAndFlush(audit);
    }

    private MeetingAction action(Instant createdAt, String description) {
        UUID org = UUID.randomUUID();
        MeetingAction action = new MeetingAction();
        action.setMeetingId(UUID.randomUUID());
        action.setTenantId(org);
        action.setOrgId(org);
        action.setDescription(description);
        action.setCreatedBySubject("creator");
        action.setLastUpdatedBySubject("creator");
        MeetingAction saved = actionRepository.saveAndFlush(action);
        setActionTimestamps(saved.getId(), createdAt);
        return saved;
    }

    private MeetingDecision decision(Instant createdAt, String title) {
        UUID org = UUID.randomUUID();
        MeetingDecision decision = new MeetingDecision();
        decision.setMeetingId(UUID.randomUUID());
        decision.setTenantId(org);
        decision.setOrgId(org);
        decision.setTitle(title);
        decision.setCreatedBySubject("creator");
        decision.setLastUpdatedBySubject("creator");
        MeetingDecision saved = decisionRepository.saveAndFlush(decision);
        setDecisionTimestamps(saved.getId(), createdAt);
        return saved;
    }

    private MeetingAnalysisRun analysisRun(Instant createdAt, boolean legalHold) {
        UUID org = UUID.randomUUID();
        MeetingAnalysisRun run = new MeetingAnalysisRun();
        run.setAnalysisRunId(UUID.randomUUID());
        run.setMeetingId(UUID.randomUUID());
        run.setTenantId(org);
        run.setOrgId(org);
        run.setTranscriptSessionId(UUID.randomUUID().toString());
        run.setTranscriptSha256("a".repeat(64));
        run.setAnalyzerContractVersion("analysis-v1");
        run.setPayloadHash("b".repeat(64));
        run.setGeneratedAt(createdAt);
        run.setLegalHold(legalHold);
        MeetingAnalysisRun saved = analysisRunRepository.saveAndFlush(run);
        entityManager.createNativeQuery("""
                        update public.meeting_analysis_runs
                        set created_at = ?, updated_at = ?
                        where analysis_run_id = ?
                        """)
                .setParameter(1, Timestamp.from(createdAt))
                .setParameter(2, Timestamp.from(createdAt))
                .setParameter(3, saved.getAnalysisRunId())
                .executeUpdate();
        entityManager.flush();
        entityManager.clear();
        return saved;
    }

    private void setActionTimestamps(UUID id, Instant timestamp) {
        entityManager.createNativeQuery("""
                        update public.meeting_actions
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

    private void setDecisionTimestamps(UUID id, Instant timestamp) {
        entityManager.createNativeQuery("""
                        update public.meeting_decisions
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

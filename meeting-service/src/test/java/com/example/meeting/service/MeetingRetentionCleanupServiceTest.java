package com.example.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.meeting.model.MeetingAction;
import com.example.meeting.model.MeetingDecision;
import com.example.meeting.model.MeetingRetentionDestructionAudit;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
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
@Import(MeetingRetentionCleanupService.class)
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
    private MeetingDecisionRepository decisionRepository;
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

        MeetingRetentionCleanupService.CleanupResult result = service.cleanup(NOW);
        entityManager.flush();
        entityManager.clear();

        assertThat(result.deletedCount()).isEqualTo(2);
        assertThat(actionRepository.findById(expiredAction.getId())).isEmpty();
        assertThat(decisionRepository.findById(expiredDecision.getId())).isEmpty();
        assertThat(actionRepository.findById(freshAction.getId())).isPresent();
        assertThat(decisionRepository.findById(freshDecision.getId())).isPresent();

        List<MeetingRetentionDestructionAudit> audits = auditRepository
                .findByLayerIdOrderByExecutedAtDesc(MeetingRetentionCleanupService.LAYER_MEETING_INTELLIGENCE);
        assertThat(audits).hasSize(1);
        MeetingRetentionDestructionAudit audit = audits.get(0);
        assertThat(audit.getDeletedCount()).isEqualTo(2);
        assertThat(audit.getActionDeletedCount()).isEqualTo(1);
        assertThat(audit.getDecisionDeletedCount()).isEqualTo(1);
        assertThat(audit.getAuditPayload()).isEqualTo("metadata-only");
        assertThat(audit.getJobId()).isEqualTo(MeetingRetentionCleanupService.JOB_ID);
        assertThat(audit.getLayerId()).isEqualTo("db.meeting-intelligence");
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

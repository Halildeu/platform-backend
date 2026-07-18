package com.example.meeting.service;

import com.example.meeting.model.MeetingRetentionDestructionAudit;
import com.example.meeting.model.MeetingAnalysisRun;
import com.example.meeting.model.MeetingAnalysisRunDestructionReason;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
import com.example.meeting.repository.MeetingIntelligenceResultAccessAuditRepository;
import com.example.meeting.repository.MeetingAnalysisRunRepository;
import com.example.meeting.repository.MeetingRetentionDestructionAuditRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Faz 24 #156 retention cleanup for meeting-intelligence outputs.
 *
 * <p>The job deletes only metadata-scoped rows selected by {@code created_at}.
 * It never reads or writes the action/decision text into audit evidence.
 */
@Service
public class MeetingRetentionCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(MeetingRetentionCleanupService.class);

    public static final String LAYER_MEETING_INTELLIGENCE = "db.meeting-intelligence";
    public static final String LAYER_RESULT_ACCESS_AUDIT = "db.meeting-intelligence-result-access-audit";
    public static final String JOB_ID = "meeting-intelligence-retention-cleanup";
    public static final String RESULT_ACCESS_AUDIT_JOB_ID =
            "meeting-intelligence-result-access-audit-retention-cleanup";

    private final MeetingActionRepository actionRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingIntelligenceResultAccessAuditRepository resultAccessAuditRepository;
    private final MeetingAnalysisRunRepository analysisRunRepository;
    private final MeetingAnalysisRunDestructionRecorder destructionRecorder;
    private final MeetingRetentionDestructionAuditRepository auditRepository;
    private final int retentionDays;
    private final int resultAccessAuditRetentionDays;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatchesPerRun;

    public MeetingRetentionCleanupService(
            MeetingActionRepository actionRepository,
            MeetingDecisionRepository decisionRepository,
            MeetingAnalysisRunRepository analysisRunRepository,
            MeetingAnalysisRunDestructionRecorder destructionRecorder,
            MeetingIntelligenceResultAccessAuditRepository resultAccessAuditRepository,
            MeetingRetentionDestructionAuditRepository auditRepository,
            @Value("${meeting.retention.meeting-intelligence-days:730}") int retentionDays,
            @Value("${meeting.retention.result-access-audit-days:730}") int resultAccessAuditRetentionDays,
            @Value("${meeting.retention.cleanup-batch-size:1000}") int cleanupBatchSize,
            @Value("${meeting.retention.cleanup-max-batches-per-run:100}") int cleanupMaxBatchesPerRun) {
        this.actionRepository = actionRepository;
        this.decisionRepository = decisionRepository;
        this.analysisRunRepository = analysisRunRepository;
        this.destructionRecorder = destructionRecorder;
        this.resultAccessAuditRepository = resultAccessAuditRepository;
        this.auditRepository = auditRepository;
        this.retentionDays = retentionDays;
        this.resultAccessAuditRetentionDays = resultAccessAuditRetentionDays;
        this.cleanupBatchSize = cleanupBatchSize;
        this.cleanupMaxBatchesPerRun = cleanupMaxBatchesPerRun;
    }

    @Scheduled(cron = "${meeting.retention.cleanup-cron:0 31 2 * * *}")
    @Transactional
    public void runScheduledCleanup() {
        cleanup(Instant.now());
    }

    @Transactional
    public CleanupResult cleanup(Instant now) {
        if (retentionDays <= 0) {
            throw new IllegalStateException("meeting.retention.meeting-intelligence-days must be > 0");
        }
        if (resultAccessAuditRetentionDays <= 0) {
            throw new IllegalStateException("meeting.retention.result-access-audit-days must be > 0");
        }
        if (cleanupBatchSize <= 0) {
            throw new IllegalStateException("meeting.retention.cleanup-batch-size must be > 0");
        }
        if (cleanupMaxBatchesPerRun <= 0) {
            throw new IllegalStateException("meeting.retention.cleanup-max-batches-per-run must be > 0");
        }
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        Instant resultAccessAuditCutoff = now.minus(Duration.ofDays(resultAccessAuditRetentionDays));
        long analysisRunCount = deleteExpiredAnalysisRuns(cutoff, now);
        // Legacy/manual children have no analysis-run parent and retain their
        // existing timestamp-based lifecycle.
        long actionCount = deleteExpiredActions(cutoff);
        long decisionCount = deleteExpiredDecisions(cutoff);
        long resultAccessAuditCount = deleteExpiredResultAccessAudits(resultAccessAuditCutoff);
        long total = analysisRunCount + actionCount + decisionCount;

        MeetingRetentionDestructionAudit audit = writeAudit(
                now, cutoff, LAYER_MEETING_INTELLIGENCE, JOB_ID,
                total, actionCount, decisionCount, 0, analysisRunCount);
        MeetingRetentionDestructionAudit resultAccessAudit = writeAudit(
                now, resultAccessAuditCutoff, LAYER_RESULT_ACCESS_AUDIT,
                RESULT_ACCESS_AUDIT_JOB_ID, resultAccessAuditCount, 0, 0,
                resultAccessAuditCount, 0);

        LOGGER.info(
                "meeting retention cleanup completed layer={} cutoff={} actionDeletedCount={} "
                        + "decisionDeletedCount={} analysisRunDeletedCount={} resultAccessAuditDeletedCount={} "
                        + "deletedCount={} jobId={}",
                LAYER_MEETING_INTELLIGENCE,
                cutoff,
                actionCount,
                decisionCount,
                analysisRunCount,
                resultAccessAuditCount,
                total + resultAccessAuditCount,
                JOB_ID);
        return new CleanupResult(
                cutoff,
                resultAccessAuditCutoff,
                actionCount,
                decisionCount,
                analysisRunCount,
                resultAccessAuditCount,
                audit.getId(),
                resultAccessAudit.getId());
    }

    private long deleteExpiredActions(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatchesPerRun; batch++) {
            List<UUID> ids = actionRepository.findExpiredIds(cutoff, PageRequest.of(0, cleanupBatchSize));
            if (ids.isEmpty()) {
                return deleted;
            }
            int batchDeleted = actionRepository.deleteByIdIn(ids);
            if (batchDeleted <= 0) {
                throw new IllegalStateException("meeting action retention cleanup made no progress");
            }
            deleted += batchDeleted;
        }
        if (!actionRepository.findExpiredIds(cutoff, PageRequest.of(0, 1)).isEmpty()) {
            LOGGER.warn(
                    "meeting retention cleanup batch limit reached entity=meeting_actions cutoff={} "
                            + "deletedCount={} maxBatches={} batchSize={}",
                    cutoff,
                    deleted,
                    cleanupMaxBatchesPerRun,
                    cleanupBatchSize);
        }
        return deleted;
    }

    private long deleteExpiredAnalysisRuns(Instant cutoff, Instant destroyedAt) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatchesPerRun; batch++) {
            List<UUID> ids = analysisRunRepository.findExpiredIds(
                    cutoff, PageRequest.of(0, cleanupBatchSize));
            if (ids.isEmpty()) {
                return deleted;
            }
            List<MeetingAnalysisRun> candidates = analysisRunRepository.findAllById(ids);
            int batchDeleted = analysisRunRepository.deleteByIdIn(ids);
            if (batchDeleted <= 0) {
                throw new IllegalStateException("meeting analysis-run retention cleanup made no progress");
            }
            destructionRecorder.recordDestroyed(
                    candidates, MeetingAnalysisRunDestructionReason.RETENTION, destroyedAt);
            deleted += batchDeleted;
        }
        if (!analysisRunRepository.findExpiredIds(cutoff, PageRequest.of(0, 1)).isEmpty()) {
            LOGGER.warn(
                    "meeting retention cleanup batch limit reached entity=meeting_analysis_runs cutoff={} "
                            + "deletedCount={} maxBatches={} batchSize={}",
                    cutoff,
                    deleted,
                    cleanupMaxBatchesPerRun,
                    cleanupBatchSize);
        }
        return deleted;
    }

    private long deleteExpiredDecisions(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatchesPerRun; batch++) {
            List<UUID> ids = decisionRepository.findExpiredIds(cutoff, PageRequest.of(0, cleanupBatchSize));
            if (ids.isEmpty()) {
                return deleted;
            }
            int batchDeleted = decisionRepository.deleteByIdIn(ids);
            if (batchDeleted <= 0) {
                throw new IllegalStateException("meeting decision retention cleanup made no progress");
            }
            deleted += batchDeleted;
        }
        if (!decisionRepository.findExpiredIds(cutoff, PageRequest.of(0, 1)).isEmpty()) {
            LOGGER.warn(
                    "meeting retention cleanup batch limit reached entity=meeting_decisions cutoff={} "
                            + "deletedCount={} maxBatches={} batchSize={}",
                    cutoff,
                    deleted,
                    cleanupMaxBatchesPerRun,
                    cleanupBatchSize);
        }
        return deleted;
    }

    private long deleteExpiredResultAccessAudits(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatchesPerRun; batch++) {
            List<UUID> ids = resultAccessAuditRepository
                    .findExpiredIds(cutoff, PageRequest.of(0, cleanupBatchSize));
            if (ids.isEmpty()) {
                return deleted;
            }
            int batchDeleted = resultAccessAuditRepository.deleteByIdIn(ids);
            if (batchDeleted <= 0) {
                throw new IllegalStateException("meeting result-access audit retention cleanup made no progress");
            }
            deleted += batchDeleted;
        }
        if (!resultAccessAuditRepository.findExpiredIds(cutoff, PageRequest.of(0, 1)).isEmpty()) {
            LOGGER.warn(
                    "meeting retention cleanup batch limit reached entity={} cutoff={} "
                            + "deletedCount={} maxBatches={} batchSize={}",
                    LAYER_RESULT_ACCESS_AUDIT,
                    cutoff,
                    deleted,
                    cleanupMaxBatchesPerRun,
                    cleanupBatchSize);
        }
        return deleted;
    }

    private MeetingRetentionDestructionAudit writeAudit(
            Instant now,
            Instant cutoff,
            String layerId,
            String jobId,
            long deletedCount,
            long actionDeletedCount,
            long decisionDeletedCount,
            long resultAccessAuditDeletedCount,
            long analysisRunDeletedCount) {
        MeetingRetentionDestructionAudit audit = new MeetingRetentionDestructionAudit();
        audit.setLayerId(layerId);
        audit.setCutoffAt(cutoff);
        audit.setDeletedCount(deletedCount);
        audit.setActionDeletedCount(actionDeletedCount);
        audit.setDecisionDeletedCount(decisionDeletedCount);
        audit.setResultAccessAuditDeletedCount(resultAccessAuditDeletedCount);
        audit.setAnalysisRunDeletedCount(analysisRunDeletedCount);
        audit.setJobId(jobId);
        audit.setAuditPayload("metadata-only");
        audit.setExecutedAt(now);
        return auditRepository.saveAndFlush(audit);
    }

    public record CleanupResult(
            Instant cutoffAt,
            Instant resultAccessAuditCutoffAt,
            long actionDeletedCount,
            long decisionDeletedCount,
            long analysisRunDeletedCount,
            long resultAccessAuditDeletedCount,
            UUID auditId,
            UUID resultAccessAuditAuditId
    ) {
        public long deletedCount() {
            return analysisRunDeletedCount + actionDeletedCount + decisionDeletedCount
                    + resultAccessAuditDeletedCount;
        }
    }
}

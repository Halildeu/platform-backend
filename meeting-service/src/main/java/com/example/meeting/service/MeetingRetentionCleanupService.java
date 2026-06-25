package com.example.meeting.service;

import com.example.meeting.model.MeetingRetentionDestructionAudit;
import com.example.meeting.repository.MeetingActionRepository;
import com.example.meeting.repository.MeetingDecisionRepository;
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
    public static final String JOB_ID = "meeting-intelligence-retention-cleanup";

    private final MeetingActionRepository actionRepository;
    private final MeetingDecisionRepository decisionRepository;
    private final MeetingRetentionDestructionAuditRepository auditRepository;
    private final int retentionDays;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatchesPerRun;

    public MeetingRetentionCleanupService(
            MeetingActionRepository actionRepository,
            MeetingDecisionRepository decisionRepository,
            MeetingRetentionDestructionAuditRepository auditRepository,
            @Value("${meeting.retention.meeting-intelligence-days:730}") int retentionDays,
            @Value("${meeting.retention.cleanup-batch-size:1000}") int cleanupBatchSize,
            @Value("${meeting.retention.cleanup-max-batches-per-run:100}") int cleanupMaxBatchesPerRun) {
        this.actionRepository = actionRepository;
        this.decisionRepository = decisionRepository;
        this.auditRepository = auditRepository;
        this.retentionDays = retentionDays;
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
        if (cleanupBatchSize <= 0) {
            throw new IllegalStateException("meeting.retention.cleanup-batch-size must be > 0");
        }
        if (cleanupMaxBatchesPerRun <= 0) {
            throw new IllegalStateException("meeting.retention.cleanup-max-batches-per-run must be > 0");
        }
        Instant cutoff = now.minus(Duration.ofDays(retentionDays));
        long actionCount = deleteExpiredActions(cutoff);
        long decisionCount = deleteExpiredDecisions(cutoff);
        long total = actionCount + decisionCount;

        MeetingRetentionDestructionAudit audit = new MeetingRetentionDestructionAudit();
        audit.setLayerId(LAYER_MEETING_INTELLIGENCE);
        audit.setCutoffAt(cutoff);
        audit.setDeletedCount(total);
        audit.setActionDeletedCount(actionCount);
        audit.setDecisionDeletedCount(decisionCount);
        audit.setJobId(JOB_ID);
        audit.setAuditPayload("metadata-only");
        audit.setExecutedAt(now);
        auditRepository.saveAndFlush(audit);

        LOGGER.info(
                "meeting retention cleanup completed layer={} cutoff={} actionDeletedCount={} "
                        + "decisionDeletedCount={} deletedCount={} jobId={}",
                LAYER_MEETING_INTELLIGENCE,
                cutoff,
                actionCount,
                decisionCount,
                total,
                JOB_ID);
        return new CleanupResult(cutoff, actionCount, decisionCount, audit.getId());
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

    public record CleanupResult(
            Instant cutoffAt,
            long actionDeletedCount,
            long decisionDeletedCount,
            UUID auditId
    ) {
        public long deletedCount() {
            return actionDeletedCount + decisionDeletedCount;
        }
    }
}

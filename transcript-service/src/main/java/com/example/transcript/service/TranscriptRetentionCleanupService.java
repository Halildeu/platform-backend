package com.example.transcript.service;

import com.example.transcript.model.TranscriptRetentionDestructionAudit;
import com.example.transcript.repository.TranscriptAccessAuditRepository;
import com.example.transcript.repository.TranscriptRetentionDestructionAuditRepository;
import com.example.transcript.repository.TranscriptFinalizationRepository;
import com.example.transcript.repository.TranscriptSegmentRepository;
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
 * Faz 24 #156 retention cleanup for transcript personal data and access logs.
 *
 * <p>The cleanup selectors use only timestamp metadata. The destruction audit
 * never copies transcript text, search terms, or accessor subjects.
 */
@Service
public class TranscriptRetentionCleanupService {

    private static final Logger LOGGER = LoggerFactory.getLogger(TranscriptRetentionCleanupService.class);

    public static final String LAYER_TRANSCRIPT_RECORDS = "db.transcript-records";
    public static final String LAYER_KVKK_ACCESS_LOG = "db.kvkk-access-log";
    public static final String JOB_TRANSCRIPT_RECORDS = "transcript-records-retention-cleanup";
    public static final String JOB_KVKK_ACCESS_LOG = "kvkk-access-log-retention-cleanup";

    private final TranscriptSegmentRepository segmentRepository;
    private final TranscriptAccessAuditRepository accessAuditRepository;
    private final TranscriptFinalizationRepository finalizationRepository;
    private final TranscriptRetentionDestructionAuditRepository destructionAuditRepository;
    private final int transcriptRetentionDays;
    private final int accessAuditRetentionDays;
    private final int cleanupBatchSize;
    private final int cleanupMaxBatchesPerRun;

    public TranscriptRetentionCleanupService(
            TranscriptSegmentRepository segmentRepository,
            TranscriptFinalizationRepository finalizationRepository,
            TranscriptAccessAuditRepository accessAuditRepository,
            TranscriptRetentionDestructionAuditRepository destructionAuditRepository,
            @Value("${transcript.retention.transcript-records-days:365}") int transcriptRetentionDays,
            @Value("${transcript.retention.access-audit-days:730}") int accessAuditRetentionDays,
            @Value("${transcript.retention.cleanup-batch-size:1000}") int cleanupBatchSize,
            @Value("${transcript.retention.cleanup-max-batches-per-run:100}") int cleanupMaxBatchesPerRun) {
        this.segmentRepository = segmentRepository;
        this.finalizationRepository = finalizationRepository;
        this.accessAuditRepository = accessAuditRepository;
        this.destructionAuditRepository = destructionAuditRepository;
        this.transcriptRetentionDays = transcriptRetentionDays;
        this.accessAuditRetentionDays = accessAuditRetentionDays;
        this.cleanupBatchSize = cleanupBatchSize;
        this.cleanupMaxBatchesPerRun = cleanupMaxBatchesPerRun;
    }

    @Scheduled(cron = "${transcript.retention.cleanup-cron:0 23 2 * * *}")
    @Transactional
    public void runScheduledCleanup() {
        cleanup(Instant.now());
    }

    @Transactional
    public CleanupResult cleanup(Instant now) {
        if (transcriptRetentionDays <= 0 || accessAuditRetentionDays <= 0) {
            throw new IllegalStateException("transcript retention days must be > 0");
        }
        if (cleanupBatchSize <= 0) {
            throw new IllegalStateException("transcript.retention.cleanup-batch-size must be > 0");
        }
        if (cleanupMaxBatchesPerRun <= 0) {
            throw new IllegalStateException("transcript.retention.cleanup-max-batches-per-run must be > 0");
        }
        LayerCleanupResult transcript = cleanupTranscriptRecords(now);
        LayerCleanupResult accessLog = cleanupAccessLog(now);
        return new CleanupResult(transcript, accessLog);
    }

    private LayerCleanupResult cleanupTranscriptRecords(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(transcriptRetentionDays));
        long deleted = deleteExpiredTranscriptSegments(cutoff) + deleteExpiredFinalizations(cutoff);
        UUID auditId = writeAudit(now, LAYER_TRANSCRIPT_RECORDS, JOB_TRANSCRIPT_RECORDS, cutoff, deleted);
        LOGGER.info(
                "transcript retention cleanup completed layer={} cutoff={} deletedCount={} jobId={}",
                LAYER_TRANSCRIPT_RECORDS,
                cutoff,
                deleted,
                JOB_TRANSCRIPT_RECORDS);
        return new LayerCleanupResult(LAYER_TRANSCRIPT_RECORDS, cutoff, deleted, auditId);
    }

    private LayerCleanupResult cleanupAccessLog(Instant now) {
        Instant cutoff = now.minus(Duration.ofDays(accessAuditRetentionDays));
        long deleted = deleteExpiredAccessAuditRows(cutoff);
        UUID auditId = writeAudit(now, LAYER_KVKK_ACCESS_LOG, JOB_KVKK_ACCESS_LOG, cutoff, deleted);
        LOGGER.info(
                "transcript retention cleanup completed layer={} cutoff={} deletedCount={} jobId={}",
                LAYER_KVKK_ACCESS_LOG,
                cutoff,
                deleted,
                JOB_KVKK_ACCESS_LOG);
        return new LayerCleanupResult(LAYER_KVKK_ACCESS_LOG, cutoff, deleted, auditId);
    }

    private long deleteExpiredTranscriptSegments(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatchesPerRun; batch++) {
            List<UUID> ids = segmentRepository.findExpiredIds(cutoff, PageRequest.of(0, cleanupBatchSize));
            if (ids.isEmpty()) {
                return deleted;
            }
            int batchDeleted = segmentRepository.deleteByIdIn(ids);
            if (batchDeleted <= 0) {
                throw new IllegalStateException("transcript segment retention cleanup made no progress");
            }
            deleted += batchDeleted;
        }
        if (!segmentRepository.findExpiredIds(cutoff, PageRequest.of(0, 1)).isEmpty()) {
            LOGGER.warn(
                    "transcript retention cleanup batch limit reached layer={} cutoff={} "
                            + "deletedCount={} maxBatches={} batchSize={}",
                    LAYER_TRANSCRIPT_RECORDS,
                    cutoff,
                    deleted,
                    cleanupMaxBatchesPerRun,
                    cleanupBatchSize);
        }
        return deleted;
    }

    private long deleteExpiredAccessAuditRows(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatchesPerRun; batch++) {
            List<UUID> ids = accessAuditRepository.findExpiredIds(cutoff, PageRequest.of(0, cleanupBatchSize));
            if (ids.isEmpty()) {
                return deleted;
            }
            int batchDeleted = accessAuditRepository.deleteByIdIn(ids);
            if (batchDeleted <= 0) {
                throw new IllegalStateException("transcript access-audit retention cleanup made no progress");
            }
            deleted += batchDeleted;
        }
        if (!accessAuditRepository.findExpiredIds(cutoff, PageRequest.of(0, 1)).isEmpty()) {
            LOGGER.warn(
                    "transcript retention cleanup batch limit reached layer={} cutoff={} "
                            + "deletedCount={} maxBatches={} batchSize={}",
                    LAYER_KVKK_ACCESS_LOG,
                    cutoff,
                    deleted,
                    cleanupMaxBatchesPerRun,
                    cleanupBatchSize);
        }
        return deleted;
    }

    private long deleteExpiredFinalizations(Instant cutoff) {
        long deleted = 0;
        for (int batch = 0; batch < cleanupMaxBatchesPerRun; batch++) {
            List<UUID> ids = finalizationRepository.findExpiredIds(
                    cutoff, PageRequest.of(0, cleanupBatchSize));
            if (ids.isEmpty()) {
                return deleted;
            }
            int batchDeleted = finalizationRepository.deleteByIdIn(ids);
            if (batchDeleted <= 0) {
                throw new IllegalStateException("transcript finalization retention cleanup made no progress");
            }
            deleted += batchDeleted;
        }
        if (!finalizationRepository.findExpiredIds(cutoff, PageRequest.of(0, 1)).isEmpty()) {
            LOGGER.warn(
                    "transcript retention cleanup batch limit reached entity=transcript_finalizations cutoff={} "
                            + "deletedCount={} maxBatches={} batchSize={}",
                    cutoff,
                    deleted,
                    cleanupMaxBatchesPerRun,
                    cleanupBatchSize);
        }
        return deleted;
    }

    private UUID writeAudit(Instant now, String layerId, String jobId, Instant cutoff, long count) {
        TranscriptRetentionDestructionAudit audit = new TranscriptRetentionDestructionAudit();
        audit.setLayerId(layerId);
        audit.setCutoffAt(cutoff);
        audit.setDeletedCount(count);
        audit.setJobId(jobId);
        audit.setAuditPayload("metadata-only");
        audit.setExecutedAt(now);
        destructionAuditRepository.saveAndFlush(audit);
        return audit.getId();
    }

    public record CleanupResult(
            LayerCleanupResult transcriptRecords,
            LayerCleanupResult kvkkAccessLog
    ) {
        public long deletedCount() {
            return transcriptRecords.deletedCount + kvkkAccessLog.deletedCount;
        }
    }

    public record LayerCleanupResult(
            String layerId,
            Instant cutoffAt,
            long deletedCount,
            UUID auditId
    ) {
    }
}

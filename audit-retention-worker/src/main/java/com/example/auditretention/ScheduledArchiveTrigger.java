package com.example.auditretention;

import com.example.auditretention.archive.ArchiveService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — Deployment-mode trigger. Only
 * created when {@code audit.retention.scheduler.enabled=true}; runs a fixed-delay
 * archive pass. Mutually exclusive with the run-once CronJob path. Errors are
 * swallowed (logged) so the always-on pod keeps scraping metrics; the failure is
 * visible via {@code audit_archive_errors_total} + a stalled cursor.
 */
@Component
@ConditionalOnProperty(name = "audit.retention.scheduler.enabled", havingValue = "true")
public class ScheduledArchiveTrigger {

    private static final Logger log = LoggerFactory.getLogger(ScheduledArchiveTrigger.class);

    private final ArchiveService archiveService;

    public ScheduledArchiveTrigger(ArchiveService archiveService) {
        this.archiveService = archiveService;
    }

    @Scheduled(fixedDelayString = "${audit.retention.scheduler.fixed-delay-millis:900000}",
            initialDelayString = "${audit.retention.scheduler.initial-delay-millis:30000}")
    public void trigger() {
        try {
            archiveService.runOnce();
        } catch (RuntimeException ex) {
            log.error("scheduled archive pass FAILED (fail-closed; cursor not advanced): {}", ex.getMessage(), ex);
        }
    }
}

package com.example.auditretention;

import com.example.auditretention.archive.ArchiveService;
import com.example.auditretention.archive.ArchiveService.ArchiveRunResult;
import com.example.auditretention.config.AuditRetentionProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.stereotype.Component;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — run-once entrypoint (CronJob mode).
 * Performs a single {@link ArchiveService#runOnce()} pass and yields an exit
 * code (0 success / 1 failure) so a Kubernetes CronJob marks the Job
 * Succeeded/Failed. Disabled when the Deployment scheduler is active.
 */
@Component
public class ArchiveRunner implements CommandLineRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(ArchiveRunner.class);

    private final AuditRetentionProperties props;
    private final ArchiveService archiveService;
    private volatile int exitCode = 0;

    public ArchiveRunner(AuditRetentionProperties props, ArchiveService archiveService) {
        this.props = props;
        this.archiveService = archiveService;
    }

    @Override
    public void run(String... args) {
        if (!props.isRunOnce() || props.getScheduler().isEnabled()) {
            log.info("run-once disabled (runOnce={}, scheduler.enabled={}) — staying up",
                    props.isRunOnce(), props.getScheduler().isEnabled());
            return;
        }
        try {
            ArchiveRunResult result = archiveService.runOnce();
            log.info("run-once archive pass OK: rows={} segments={} lagSeconds={}",
                    result.rowsArchived(), result.segmentsWritten(), result.lagSeconds());
            exitCode = 0;
        } catch (RuntimeException ex) {
            // Fail-closed: a chain break / anomaly / error must surface as a failed
            // Job so the alerting + operator path engages (cursor stays put).
            log.error("run-once archive pass FAILED (fail-closed; cursor not advanced): {}", ex.getMessage(), ex);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}

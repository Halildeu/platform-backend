package com.example.auditretention;

import com.example.auditretention.config.AuditRetentionProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — audit-retention-worker entrypoint
 * (ADR-0042). Standalone WORM archival worker.
 *
 * <p>Run-once (CronJob, default): the {@link ArchiveRunner} CommandLineRunner
 * performs a single pass, then {@code main} drives a clean exit with its exit
 * code so the Job is marked Succeeded/Failed. Deployment-mode (scheduler
 * enabled): the context stays up and {@link ScheduledArchiveTrigger} drives
 * fixed-delay passes while {@code /actuator/prometheus} is scraped.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties(AuditRetentionProperties.class)
public class AuditRetentionWorkerApplication {

    public static void main(String[] args) {
        ConfigurableApplicationContext ctx = SpringApplication.run(AuditRetentionWorkerApplication.class, args);
        AuditRetentionProperties props = ctx.getBean(AuditRetentionProperties.class);
        if (props.isRunOnce() && !props.getScheduler().isEnabled()) {
            // CronJob mode: the CommandLineRunner already ran; exit with its code
            // (SpringApplication.exit invokes the ArchiveRunner ExitCodeGenerator).
            int code = SpringApplication.exit(ctx);
            System.exit(code);
        }
        // Deployment/scheduler mode: stay running for scheduled passes + scrape.
    }
}

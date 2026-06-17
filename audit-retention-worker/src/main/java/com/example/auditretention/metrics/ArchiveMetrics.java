package com.example.auditretention.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — archival worker telemetry
 * (ADR-0042 §4 observability). The durable signal is the DB cursor/ledger; for
 * a run-once CronJob these in-process meters are ephemeral but feed a
 * Deployment-mode scrape and structured logs.
 */
@Component
public class ArchiveMetrics {

    private final Counter rowsArchived;
    private final Counter segmentsWritten;
    private final Counter chainBreaks;
    private final Counter anomalies;
    private final Counter errors;
    private final Counter idempotentSkips;
    private final AtomicLong lagSeconds = new AtomicLong(0);
    private final AtomicLong lastRunRowsArchived = new AtomicLong(0);

    public ArchiveMetrics(MeterRegistry registry) {
        this.rowsArchived = Counter.builder("audit_archive_rows_archived_total")
                .description("audit_event rows written to WORM cold archive").register(registry);
        this.segmentsWritten = Counter.builder("audit_archive_segments_written_total")
                .description("archive segment objects written (NDJSON.gz + manifest)").register(registry);
        this.chainBreaks = Counter.builder("audit_archive_chain_break_total")
                .description("per-tenant hash-chain breaks detected (fail-closed, no archive)").register(registry);
        this.anomalies = Counter.builder("audit_archive_anomaly_total")
                .description("S3 object/version anomalies (unexpected latest version, ledger-absent object)").register(registry);
        this.errors = Counter.builder("audit_archive_errors_total")
                .description("archival run errors").register(registry);
        this.idempotentSkips = Counter.builder("audit_archive_idempotent_skip_total")
                .description("segments already archived (version-id HEAD-reuse), put skipped").register(registry);
        registry.gauge("audit_archive_lag_seconds", lagSeconds, AtomicLong::get);
        registry.gauge("audit_archive_last_run_rows_archived", lastRunRowsArchived, AtomicLong::get);
    }

    public void rowsArchived(long n) {
        rowsArchived.increment(n);
    }

    public void segmentWritten() {
        segmentsWritten.increment();
    }

    public void chainBreak() {
        chainBreaks.increment();
    }

    public void anomaly() {
        anomalies.increment();
    }

    public void error() {
        errors.increment();
    }

    public void idempotentSkip() {
        idempotentSkips.increment();
    }

    public void setLagSeconds(long seconds) {
        lagSeconds.set(seconds);
    }

    public void setLastRunRowsArchived(long n) {
        lastRunRowsArchived.set(n);
    }
}

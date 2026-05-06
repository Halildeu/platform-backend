package com.serban.notify.audit;

import com.serban.notify.config.NotifyConfig;
import com.serban.notify.domain.AuditRetentionLog;
import com.serban.notify.repository.AuditRetentionLogRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Audit partition retention service (Faz 23.2 PR-D.1 — Codex 019dfdec Q5 absorb).
 *
 * <p>Three-step daily cycle:
 * <ol>
 *   <li><b>Future ensure</b>: idempotent CREATE next N month partitions (Codex
 *       Q4 REVISE absorb — DEFAULT partition'a normal-ay verisi düşmesin).</li>
 *   <li><b>Detach phase</b>: partition {@code range_end} ≤ now - retentionDays
 *       → DETACH PARTITION + audit_retention_log row (status=detached).</li>
 *   <li><b>Drop phase</b>: detached partitions with {@code drop_after} ≤ now →
 *       DROP TABLE + UPDATE log row (status=dropped, dropped_at=now).</li>
 * </ol>
 *
 * <p>Multi-pod safe: {@code pg_try_advisory_xact_lock} on stable hash. Only one
 * pod processes per cycle; concurrent runs skip with metric increment.
 *
 * <p>Default partition guard: separate gauge {@code notify.audit.retention.default_partition.row_count}
 * — operator alert if non-zero (means a row landed in DEFAULT, partition ensure
 * missed coverage).
 *
 * <p>Identifier safety: partition names validated against allowlist regex
 * {@code audit_event_v2_\d{4}_\d{2}|audit_event_v2_default} before SQL injection
 * (Codex Q5 PARTIAL absorb).
 */
@Service
@ConditionalOnProperty(name = "notify.audit.retention-enabled", havingValue = "true")
public class AuditPartitionRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AuditPartitionRetentionService.class);

    /** Partition naming: audit_event_v2_YYYY_MM (regular) or audit_event_v2_default. */
    private static final Pattern PARTITION_NAME_PATTERN =
        Pattern.compile("^audit_event_v2_(\\d{4}_\\d{2}|default)$");

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MM");
    private static final long ADVISORY_LOCK_KEY = stableLockKey("notify.audit.retention");

    private final JdbcTemplate jdbc;
    private final AuditRetentionLogRepository logRepo;
    private final NotifyConfig.AuditConfig cfg;
    private final boolean schedulingEnabled;
    private final Counter detachedCounter;
    private final Counter droppedCounter;
    private final Counter lockSkippedCounter;
    private final Counter errorsCounter;
    private final Counter futurePartitionsCreatedCounter;
    private final AtomicLong lastSuccessTimestamp = new AtomicLong(0);
    private AuditPartitionRetentionService self;  // self-injection for proxy

    public AuditPartitionRetentionService(
        JdbcTemplate jdbc,
        AuditRetentionLogRepository logRepo,
        NotifyConfig notifyConfig,
        MeterRegistry meterRegistry
    ) {
        this.jdbc = jdbc;
        this.logRepo = logRepo;
        this.cfg = notifyConfig.audit();
        this.schedulingEnabled = cfg.retentionSchedulingEnabled();
        this.detachedCounter = Counter.builder("notify.audit.retention.partitions.detached")
            .register(meterRegistry);
        this.droppedCounter = Counter.builder("notify.audit.retention.partitions.dropped")
            .register(meterRegistry);
        this.lockSkippedCounter = Counter.builder("notify.audit.retention.lock.skipped")
            .register(meterRegistry);
        this.errorsCounter = Counter.builder("notify.audit.retention.errors")
            .tags(Tags.of("phase", "unknown"))
            .register(meterRegistry);
        this.futurePartitionsCreatedCounter = Counter.builder("notify.audit.retention.future_partitions.created")
            .register(meterRegistry);
        meterRegistry.gauge("notify.audit.retention.last_success.timestamp_seconds",
            lastSuccessTimestamp, AtomicLong::get);
        log.info("AuditPartitionRetentionService activated: retentionDays={} cron={} graceHours={} "
            + "dryRun={} futureMonths={} schedulingEnabled={}",
            cfg.retentionDays(), cfg.retentionCron(), cfg.retentionGraceHours(),
            cfg.retentionDryRun(), cfg.retentionFutureMonths(), schedulingEnabled);
    }

    @org.springframework.beans.factory.annotation.Autowired
    void setSelf(@org.springframework.context.annotation.Lazy AuditPartitionRetentionService self) {
        this.self = self;
    }

    /** Scheduled tick (cron-driven). Test isolation: scheduling-enabled=false → manual runCycle(). */
    @Scheduled(cron = "${notify.audit.retention-cron:0 0 2 * * *}", zone = "UTC")
    public void tick() {
        if (!schedulingEnabled) return;
        runCycle();
    }

    /** Public entry — called by @Scheduled tick OR directly by tests. */
    public void runCycle() {
        try {
            CycleResult result = self.cycle();
            // Codex 019dfdec iter-1 P2 absorb: last_success only on real success
            // (lock skip / error don't update gauge). Alarm rule "now() - gauge > 26h"
            // sinyalize eder cycle gerçekten failing/skipped olursa.
            if (result.successful()) {
                lastSuccessTimestamp.set(OffsetDateTime.now().toEpochSecond());
            }
        } catch (RuntimeException e) {
            log.warn("AuditPartitionRetentionService cycle error: {}", e.getMessage(), e);
            errorsCounter.increment();
        }
    }

    /** Single cycle within a transaction (advisory_xact_lock semantics). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public CycleResult cycle() {
        Boolean acquired = jdbc.queryForObject(
            "SELECT pg_try_advisory_xact_lock(?)", Boolean.class, ADVISORY_LOCK_KEY);
        if (acquired == null || !acquired) {
            log.info("AuditPartitionRetentionService cycle skipped — advisory lock contention");
            lockSkippedCounter.increment();
            return CycleResult.lockSkipped();
        }

        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int futureCreated;
        int detached;
        int dropped;
        try {
            futureCreated = ensureFuturePartitions(now);
            if (futureCreated > 0) {
                futurePartitionsCreatedCounter.increment(futureCreated);
                log.info("AuditPartitionRetentionService: created {} future partitions", futureCreated);
            }

            detached = detachOldPartitions(now);
            dropped = dropEligiblePartitions(now);
        } catch (RuntimeException e) {
            log.warn("AuditPartitionRetentionService cycle inner error: {}", e.getMessage(), e);
            errorsCounter.increment();
            return CycleResult.error();
        }

        log.info("AuditPartitionRetentionService cycle: future_created={} detached={} dropped={} dry_run={}",
            futureCreated, detached, dropped, cfg.retentionDryRun());
        return CycleResult.success(futureCreated, detached, dropped);
    }

    /** Cycle outcome — caller (runCycle) uses successful flag for last_success gauge. */
    public record CycleResult(boolean successful, int futureCreated, int detached, int dropped) {
        public static CycleResult success(int fc, int d, int dr) { return new CycleResult(true, fc, d, dr); }
        public static CycleResult lockSkipped() { return new CycleResult(false, 0, 0, 0); }
        public static CycleResult error() { return new CycleResult(false, 0, 0, 0); }
    }

    /**
     * Ensure current month + N future months partitions exist. Idempotent
     * (CREATE TABLE IF NOT EXISTS pattern). Returns count of newly-created
     * partitions.
     */
    int ensureFuturePartitions(OffsetDateTime now) {
        int created = 0;
        YearMonth start = YearMonth.from(now);
        for (int i = 0; i <= cfg.retentionFutureMonths(); i++) {
            YearMonth ym = start.plusMonths(i);
            String partitionName = "audit_event_v2_" + ym.format(MONTH_FMT);
            if (!isValidPartitionName(partitionName)) continue;  // safety
            if (partitionExists(partitionName)) continue;
            String rangeStart = ym.atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
            String rangeEnd = ym.plusMonths(1).atDay(1).atStartOfDay().atOffset(ZoneOffset.UTC).toString();
            if (cfg.retentionDryRun()) {
                log.info("[dry-run] would CREATE partition {} FOR VALUES FROM ('{}') TO ('{}')",
                    partitionName, rangeStart, rangeEnd);
                continue;
            }
            jdbc.execute(String.format(
                "CREATE TABLE IF NOT EXISTS notify.%s PARTITION OF notify.audit_event_v2 "
                    + "FOR VALUES FROM ('%s') TO ('%s')",
                partitionName, rangeStart, rangeEnd
            ));
            log.info("Created future partition: {} ({} → {})", partitionName, rangeStart, rangeEnd);
            created++;
        }
        return created;
    }

    /**
     * Detach partitions whose range_end ≤ now - retentionDays. Insert audit_retention_log row.
     * Returns count of detached partitions.
     */
    int detachOldPartitions(OffsetDateTime now) {
        OffsetDateTime cutoff = now.minus(Duration.ofDays(cfg.retentionDays()));
        List<Map<String, Object>> partitions = jdbc.queryForList(
            "SELECT child.relname AS partition_name, "
                + "pg_get_expr(child.relpartbound, child.oid) AS bound_expr "
                + "FROM pg_inherits inh "
                + "JOIN pg_class child ON child.oid = inh.inhrelid "
                + "JOIN pg_class parent ON parent.oid = inh.inhparent "
                + "JOIN pg_namespace ns ON ns.oid = parent.relnamespace "
                + "WHERE ns.nspname = 'notify' AND parent.relname = 'audit_event_v2' "
                + "AND child.relname != 'audit_event_v2_default'"
        );
        int detached = 0;
        for (Map<String, Object> row : partitions) {
            String name = (String) row.get("partition_name");
            String bound = (String) row.get("bound_expr");
            if (!isValidPartitionName(name)) continue;
            ParsedRange range = parseBoundExpression(bound);
            if (range == null) continue;
            if (range.rangeEnd.isAfter(cutoff)) continue;  // not eligible
            if (logRepo.findByPartitionName(name).isPresent()) continue;  // already detached

            if (cfg.retentionDryRun()) {
                log.info("[dry-run] would DETACH partition {} (range_end={} ≤ cutoff={})",
                    name, range.rangeEnd, cutoff);
                continue;
            }

            try {
                jdbc.execute(String.format(
                    "ALTER TABLE notify.audit_event_v2 DETACH PARTITION notify.%s", name));
                AuditRetentionLog logRow = new AuditRetentionLog();
                logRow.setPartitionName(name);
                logRow.setRangeStart(range.rangeStart);
                logRow.setRangeEnd(range.rangeEnd);
                logRow.setDetachedAt(now);
                logRow.setDropAfter(now.plus(Duration.ofHours(cfg.retentionGraceHours())));
                logRow.setStatus(AuditRetentionLog.Status.detached);
                logRepo.save(logRow);
                detachedCounter.increment();
                detached++;
                log.info("Detached partition: {} (range {} → {}, drop_after {})",
                    name, range.rangeStart, range.rangeEnd, logRow.getDropAfter());
            } catch (RuntimeException e) {
                log.warn("Detach failed for partition {}: {}", name, e.getMessage());
                errorsCounter.increment();
            }
        }
        return detached;
    }

    /**
     * Drop partitions whose drop_after has passed (audit_retention_log status=detached
     * + drop_after ≤ now + dropped_at IS NULL). UPDATE log row to dropped.
     */
    int dropEligiblePartitions(OffsetDateTime now) {
        List<AuditRetentionLog> eligible = logRepo.findDropEligible(now);
        int dropped = 0;
        for (AuditRetentionLog row : eligible) {
            String name = row.getPartitionName();
            if (!isValidPartitionName(name)) continue;

            if (cfg.retentionDryRun()) {
                log.info("[dry-run] would DROP partition {}", name);
                continue;
            }

            try {
                jdbc.execute(String.format("DROP TABLE IF EXISTS notify.%s", name));
                row.setStatus(AuditRetentionLog.Status.dropped);
                row.setDroppedAt(now);
                logRepo.save(row);
                droppedCounter.increment();
                dropped++;
                log.info("Dropped partition: {} (originally detached at {})",
                    name, row.getDetachedAt());
            } catch (RuntimeException e) {
                log.warn("Drop failed for partition {}: {}", name, e.getMessage());
                row.setStatus(AuditRetentionLog.Status.failed);
                row.setErrorMessage(e.getMessage());
                logRepo.save(row);
                errorsCounter.increment();
            }
        }
        return dropped;
    }

    /** Identifier safety — only allow audit_event_v2_<YYYY_MM> or _default suffix. */
    static boolean isValidPartitionName(String name) {
        return name != null && PARTITION_NAME_PATTERN.matcher(name).matches();
    }

    private boolean partitionExists(String partitionName) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_class c "
                + "JOIN pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname = 'notify' AND c.relname = ? AND c.relkind = 'r'",
            Integer.class, partitionName
        );
        return count != null && count > 0;
    }

    /**
     * Parse PG partition bound expression like:
     * {@code FOR VALUES FROM ('2026-05-01 00:00:00+00') TO ('2026-06-01 00:00:00+00')}
     */
    static ParsedRange parseBoundExpression(String bound) {
        if (bound == null) return null;
        try {
            int fromIdx = bound.indexOf("FROM ('");
            int toIdx = bound.indexOf("') TO ('");
            int endIdx = bound.lastIndexOf("')");
            if (fromIdx < 0 || toIdx < 0 || endIdx < 0) return null;
            String startStr = bound.substring(fromIdx + 7, toIdx);
            String endStr = bound.substring(toIdx + 8, endIdx);
            OffsetDateTime start = parseFlexible(startStr);
            OffsetDateTime end = parseFlexible(endStr);
            return new ParsedRange(start, end);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static OffsetDateTime parseFlexible(String s) {
        // PG bound expression format: "2026-05-01 00:00:00+00"
        String iso = s.replace(' ', 'T');
        // Convert +00 → +00:00 (ISO 8601 strict)
        if (iso.matches(".*[+-]\\d{2}$")) iso = iso + ":00";
        return OffsetDateTime.parse(iso);
    }

    /** Stable 64-bit hash for advisory lock key. Same key across pods/sessions. */
    private static long stableLockKey(String name) {
        long h = 1469598103934665603L;  // FNV offset basis
        for (byte b : name.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
            h ^= (b & 0xff);
            h *= 1099511628211L;  // FNV prime
        }
        return h;
    }

    static record ParsedRange(OffsetDateTime rangeStart, OffsetDateTime rangeEnd) {}
}

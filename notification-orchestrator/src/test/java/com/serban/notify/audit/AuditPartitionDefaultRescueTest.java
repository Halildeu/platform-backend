package com.serban.notify.audit;

import com.serban.notify.AbstractPostgresTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression test for the 2026-08 calendar-rollover incident (gitops#3460):
 * V8 pre-created monthly partitions only through 2026-07, so from Aug 1 any
 * {@code NOW()} audit row inserted before the retention cycle's ensure step
 * landed in the DEFAULT partition — and Postgres then refused
 * {@code CREATE TABLE ... PARTITION OF} for that month forever
 * ("updated partition constraint for default partition would be violated").
 * In CI this surfaced as order-dependent failures in
 * {@link AuditPartitionRetentionDetachDropTest}; in production the same
 * mechanism poisons a month whose first audit row arrives in the
 * 00:00–02:00 rollover window before the daily cron.
 *
 * <p>The scenario is simulated deterministically: drop the current-month
 * partition (as if the month just rolled over), insert a NOW() row (strands
 * in DEFAULT), then run a cycle and expect the partition to exist with the
 * row relocated into it.
 *
 * <p>retention-days=36500 keeps every real partition ineligible for detach
 * (same isolation reasoning as {@link AuditPartitionV8IntegrationTest}).
 */
@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
@ContextConfiguration(initializers = AbstractPostgresTest.Initializer.class)
@TestPropertySource(properties = {
    "notify.audit.retention-enabled=true",
    "notify.audit.retention-scheduling-enabled=false",
    "notify.audit.retention-days=36500",
    "notify.audit.retention-grace-hours=1",
    "notify.audit.retention-future-months=1",
    "notify.audit.retention-dry-run=false"
})
class AuditPartitionDefaultRescueTest extends AbstractPostgresTest {

    private static final DateTimeFormatter MONTH_FMT = DateTimeFormatter.ofPattern("yyyy_MM");

    @Autowired AuditPartitionRetentionService retentionService;
    @Autowired JdbcTemplate jdbc;

    @Test
    void cycleRescuesCurrentMonthRowsStrandedInTheDefaultPartition() {
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String partition = "audit_event_v2_" + currentMonth.format(MONTH_FMT);

        // Simulate the rollover state: the month partition does not exist yet.
        // (Another test's cycle may already have created it — shared schema.)
        if (partitionAttached(partition)) {
            jdbc.execute("ALTER TABLE notify.audit_event_v2 DETACH PARTITION notify." + partition);
        }
        jdbc.execute("DROP TABLE IF EXISTS notify." + partition);

        String intentId = "rescue-" + UUID.randomUUID();
        jdbc.update(
            "INSERT INTO notify.audit_event_v2 "
                + "(intent_id, event_type, org_id, topic_key, occurred_at) "
                + "VALUES (?, 'TEST_EVENT', 'default', 'test.topic', NOW())",
            intentId);
        assertThat(rowCount("audit_event_v2_default", intentId))
            .as("NOW() row must strand in DEFAULT while the month partition is absent")
            .isEqualTo(1);

        retentionService.runCycle();

        assertThat(partitionAttached(partition))
            .as("cycle must create the month partition despite the stranded row")
            .isTrue();
        assertThat(rowCount(partition, intentId))
            .as("stranded row must be relocated into the month partition")
            .isEqualTo(1);
        assertThat(rowCount("audit_event_v2_default", intentId))
            .as("DEFAULT must no longer hold the rescued row")
            .isZero();
    }

    @Test
    void cycleStaysPlainWhenDefaultHoldsNoCurrentMonthRows() {
        YearMonth currentMonth = YearMonth.now(ZoneOffset.UTC);
        String partition = "audit_event_v2_" + currentMonth.format(MONTH_FMT);
        if (partitionAttached(partition)) {
            jdbc.execute("ALTER TABLE notify.audit_event_v2 DETACH PARTITION notify." + partition);
        }
        jdbc.execute("DROP TABLE IF EXISTS notify." + partition);

        retentionService.runCycle();

        assertThat(partitionAttached(partition)).isTrue();
    }

    private boolean partitionAttached(String partitionName) {
        Integer attached = jdbc.queryForObject(
            "SELECT COUNT(*) FROM pg_inherits inh "
                + "JOIN pg_class child ON child.oid = inh.inhrelid "
                + "JOIN pg_class parent ON parent.oid = inh.inhparent "
                + "JOIN pg_namespace n ON n.oid = parent.relnamespace "
                + "WHERE n.nspname = 'notify' AND parent.relname = 'audit_event_v2' "
                + "AND child.relname = ?",
            Integer.class, partitionName);
        return attached != null && attached > 0;
    }

    private int rowCount(String tableName, String intentId) {
        Integer count = jdbc.queryForObject(
            "SELECT COUNT(*) FROM notify." + tableName + " WHERE intent_id = ?",
            Integer.class, intentId);
        return count == null ? 0 : count;
    }
}

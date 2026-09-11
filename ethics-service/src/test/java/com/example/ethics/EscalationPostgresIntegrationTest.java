package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.model.CaseEscalation;
import com.example.ethics.repository.CaseEscalationRepository;
import com.example.ethics.repository.EthicsCaseRepository;
import com.example.ethics.service.EscalationSweeper;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 35 ES-301 — what only the production engine can prove about escalation (#882).
 *
 * <p>H2 shows the contract; PostgreSQL shows the concurrency: two sweepers on the same case
 * at the same instant write one row per level, a sweep that arrives while an acknowledgement
 * holds the case row waits and then sees it, the V26 trigger refuses to edit history, and
 * the entity mapping validates against the migrated schema.
 */
@SpringBootTest(properties = {
        "ethics.participant-handle-key=test-only-participant-handle-key-0123456789",
        "ethics.sla.escalation.enabled=true",
        "ethics.sla.escalation.initial-delay=PT720H",
        "ethics.sla.escalation.steps=PT0S,P3D",
        "ethics.notification-delivery.escalation-signals-enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate"})
@Testcontainers(disabledWithoutDocker = true)
class EscalationPostgresIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final Instant NOW = Instant.parse("2026-07-29T12:00:00Z");

    @Autowired EscalationSweeper sweeper;
    @Autowired CaseEscalationRepository escalations;
    @Autowired EthicsCaseRepository cases;
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate tx;
    @Autowired com.example.ethics.repository.AuditOutboxRepository auditOutbox;
    @Autowired com.example.ethics.service.CaseSlaClock slaClock;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired com.example.ethics.notification.NotificationOutboxPublisher notifications;
    @Autowired com.example.ethics.notification.NotificationSignalBudget signalBudget;
    @Autowired com.example.ethics.config.NotificationDeliveryProperties delivery;

    private UUID insertCase(UUID orgId, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ethics_service.ethics_cases
                    (id, org_id, product_id, status, version, created_at, updated_at)
                VALUES (?, ?, 'etik-speak', 'NEW', 0, ?, ?)
                """, id, orgId, Timestamp.from(createdAt), Timestamp.from(createdAt));
        return id;
    }

    @Test
    @DisplayName("iki eşzamanlı tarama aynı vakaya seviye başına tek satır yazar; kiracılar karışmaz")
    void twoConcurrentSweepsWriteOneRowPerLevel() throws Exception {
        UUID orgA = UUID.fromString("00000000-0000-0000-0000-00000000e5a1");
        UUID orgB = UUID.fromString("00000000-0000-0000-0000-00000000e5b2");
        UUID a = insertCase(orgA, NOW.minus(Duration.ofDays(12)));
        UUID b = insertCase(orgB, NOW.minus(Duration.ofDays(12)));

        var start = new CountDownLatch(1);
        List<EscalationSweeper.CycleResult> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return sweeper.runCycle(NOW); });
            var second = pool.submit(() -> { start.await(); return sweeper.runCycle(NOW); });
            start.countDown();
            results = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
        }

        assertThat(results).allSatisfy(r -> assertThat(r.failed()).isZero());
        for (UUID caseId : List.of(a, b)) {
            var rows = escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId);
            assertThat(rows).extracting(CaseEscalation::getLevel).containsExactly(1, 2);
            assertThat(rows).extracting(CaseEscalation::getObligation)
                    .containsOnly(CaseEscalation.ACKNOWLEDGEMENT);
        }
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(a))
                .extracting(CaseEscalation::getOrgId).containsOnly(orgA);
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(b))
                .extracting(CaseEscalation::getOrgId).containsOnly(orgB);
        Integer auditRows = jdbc.queryForObject("""
                SELECT COUNT(*) FROM ethics_service.ethics_audit_outbox
                 WHERE aggregate_id IN (?, ?) AND event_type = ?
                """, Integer.class, a, b, EscalationSweeper.EVENT_TYPE);
        assertThat(auditRows).as("her satır için tam bir denetim olayı").isEqualTo(4);
    }

    /**
     * NOWAIT on the production engine. A sweep that arrives while an acknowledgement
     * transaction holds the case row does not queue behind it and does not decide from a
     * stale snapshot: the case is deferred, every case behind it is still processed, and
     * the next cycle sees the committed acknowledgement.
     */
    @Test
    @DisplayName("tutulan satır ertelenir, arkasındaki vaka işlenir, sonraki tur onayı görür")
    void aHeldRowIsDeferredWithoutStallingTheRest() throws Exception {
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000e5c3");
        // Older createdAt sorts first in the candidate list, so the held case is in front.
        UUID held = insertCase(orgId, NOW.minus(Duration.ofDays(13)));
        UUID behind = insertCase(orgId, NOW.minus(Duration.ofDays(12)));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var acknowledging = pool.submit(() -> tx.execute(status -> {
                cases.lockById(held).orElseThrow();
                locked.countDown();
                try { release.await(30, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return cases.markAcknowledged(held, NOW.minusSeconds(1));
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            var whileHeld = sweeper.runCycle(NOW);

            release.countDown();
            assertThat(acknowledging.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(whileHeld.deferred()).as("tutulan satır ertelenmeli").isEqualTo(1);
            assertThat(whileHeld.failed()).isZero();
        }
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(behind))
                .as("tutulan satırın arkasındaki vaka aynı turda işlenmeli")
                .extracting(CaseEscalation::getLevel).containsExactly(1, 2);
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(held)).isEmpty();

        var nextCycle = sweeper.runCycle(NOW.plusSeconds(1));

        assertThat(nextCycle.deferred()).isZero();
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(held))
                .as("kilit sırasında onaylanan vaka sonraki turda da seviye almamalı")
                .isEmpty();
    }

    /**
     * Java compares in nanoseconds, PostgreSQL stores microseconds. The sweeper truncates
     * {@code now} first, so what it decides is what the CHECK constraint sees: a nanosecond
     * past the threshold writes nothing, a microsecond past it writes the row.
     */
    @Test
    @DisplayName("eşiğin bir nanosaniye sonrası satır yazmaz; bir mikrosaniye sonrası yazar")
    void theThresholdBoundaryAgreesWithTheDatabasePrecision() {
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000e5f6");
        UUID caseId = insertCase(orgId, NOW.minus(Duration.ofDays(7))); // acknowledgement due at NOW exactly

        var nano = sweeper.runCycle(NOW.plusNanos(1));
        assertThat(nano.failed()).as("CHECK kısıtı transaction'ı reddetmemeli").isZero();
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId)).isEmpty();

        var micro = sweeper.runCycle(NOW.plusNanos(1_000));
        assertThat(micro.failed()).isZero();
        var rows = escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId);
        assertThat(rows).extracting(CaseEscalation::getLevel).containsExactly(1);
        assertThat(rows.get(0).getEscalatedAt()).isEqualTo(NOW.plusNanos(1_000));
    }

    /**
     * A step configured below the database's precision. Without the shared microsecond
     * contract Java would reach level 1 at due + 1 µs against a threshold of due + 999 ns,
     * PostgreSQL would round the threshold up to the same microsecond, and the row would fail
     * {@code ck_ethics_escalation_after_threshold} inside the case transaction.
     */
    @Test
    @DisplayName("mikrosaniye altı adım CHECK kısıtını düşürmez — eşik ve kayıt aynı hassasiyette")
    void aSubMicrosecondStepAgreesWithTheCheckConstraint() {
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000e5a7");
        UUID caseId = insertCase(orgId, NOW.minus(Duration.ofDays(7))); // due at NOW exactly
        var fractional = new EscalationSweeper(cases, escalations, auditOutbox, slaClock,
                new com.example.ethics.config.EthicsSlaEscalationProperties(true, List.of(Duration.ofNanos(999))),
                transactions, objectMapper, new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                notifications, signalBudget, delivery);

        var result = fractional.runCycle(NOW.plusNanos(1_000));

        assertThat(result.failed()).as("CHECK kısıtı transaction'ı reddetmemeli").isZero();
        var rows = escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId);
        assertThat(rows).extracting(CaseEscalation::getLevel).containsExactly(1);
        assertThat(rows.get(0).getThresholdAt()).isEqualTo(NOW);
        assertThat(rows.get(0).getEscalatedAt()).isEqualTo(NOW.plusNanos(1_000));
    }

    @Test
    @DisplayName("PostgreSQL eskalasyon satırının değiştirilmesini ve silinmesini reddeder")
    void escalationRowsAreAppendOnlyOnPostgres() {
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000e5d4");
        UUID caseId = insertCase(orgId, NOW.minus(Duration.ofDays(8)));
        sweeper.runCycle(NOW);
        var row = escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId).get(0);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ethics_service.ethics_case_escalation SET level = 5 WHERE id = ?", row.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ethics_service.ethics_case_escalation WHERE id = ?", row.getId()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(escalations.findById(row.getId())).isPresent();
    }

    @Test
    @DisplayName("aynı seviye için ikinci satır PostgreSQL'de de reddedilir")
    void theLevelIndexHoldsOnPostgres() {
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000e5e5");
        UUID caseId = insertCase(orgId, NOW.minus(Duration.ofDays(8)));
        sweeper.runCycle(NOW);
        var row = escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId).get(0);

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ethics_service.ethics_case_escalation
                    (id, case_id, org_id, obligation, level, due_at, threshold_at, escalated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), caseId, orgId, row.getObligation(), row.getLevel(),
                Timestamp.from(row.getDueAt()), Timestamp.from(row.getThresholdAt()), Timestamp.from(NOW)))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining(EscalationSweeper.LEVEL_INDEX);
    }

    // ── ES-301b (#1153): one signal per organisation, per level, per rolling day ────────

    private long outboxRows(UUID orgId, String eventType) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM ethics_service.ethics_notification_outbox
                WHERE org_id = ? AND event_type = ?
                """, Long.class, orgId, eventType);
    }

    @Test
    @DisplayName("aynı kurumun iki vakası iki eşzamanlı taramada seviye başına tek bildirim sinyali üretir")
    void twoCasesOfOneOrganisationSignalEachLevelOnce() throws Exception {
        UUID org = UUID.fromString("00000000-0000-0000-0000-0000000f1150");
        insertCase(org, NOW.minus(Duration.ofDays(12)));
        insertCase(org, NOW.minus(Duration.ofDays(12)));

        var start = new CountDownLatch(1);
        List<EscalationSweeper.CycleResult> results;
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> { start.await(); return sweeper.runCycle(NOW); });
            var second = pool.submit(() -> { start.await(); return sweeper.runCycle(NOW); });
            start.countDown();
            results = List.of(first.get(60, TimeUnit.SECONDS), second.get(60, TimeUnit.SECONDS));
        }

        assertThat(results).allSatisfy(r -> assertThat(r.failed()).isZero());
        assertThat(results.stream().mapToInt(EscalationSweeper.CycleResult::recorded).sum()).isEqualTo(4);
        assertThat(outboxRows(org, "CASE_ESCALATED_L1")).as("L1: tek sinyal").isEqualTo(1);
        assertThat(outboxRows(org, "CASE_ESCALATED_L2")).as("L2: tek sinyal").isEqualTo(1);

        // A third case a minute later, same day: levels recorded, no new signal.
        insertCase(org, NOW.minus(Duration.ofDays(12)));
        var later = sweeper.runCycle(NOW.plus(Duration.ofMinutes(1)));
        assertThat(later.recorded()).isEqualTo(2);
        assertThat(outboxRows(org, "CASE_ESCALATED_L1")).isEqualTo(1);
        assertThat(outboxRows(org, "CASE_ESCALATED_L2")).isEqualTo(1);

        // Past the rolling day the budget opens again.
        insertCase(org, NOW.minus(Duration.ofDays(12)));
        sweeper.runCycle(NOW.plus(Duration.ofHours(25)));
        assertThat(outboxRows(org, "CASE_ESCALATED_L1")).isEqualTo(2);
    }

    @Test
    @DisplayName("geri alınan vaka işlemi bildirim bütçesini de geri verir")
    void aRolledBackCaseTransactionReturnsTheSignalBudget() {
        UUID org = UUID.fromString("00000000-0000-0000-0000-0000000f1151");
        Instant now = NOW.plus(Duration.ofDays(2));
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            assertThat(signalBudget.claim(org, "CASE_ESCALATED_L3", now)).isTrue();
            throw new IllegalStateException("simulated failure after the claim");
        })).isInstanceOf(IllegalStateException.class);

        Boolean afterRollback = tx.execute(status -> signalBudget.claim(org, "CASE_ESCALATED_L3", now));
        assertThat(afterRollback).as("the rolled-back claim did not spend the budget").isTrue();
        Boolean afterCommit = tx.execute(status -> signalBudget.claim(org, "CASE_ESCALATED_L3", now.plusSeconds(60)));
        assertThat(afterCommit).as("a committed claim did").isFalse();
    }

    @Test
    @DisplayName("Java izin listesi ile etkin CHECK kısıtı aynı olay kümesini tanır (gerçek Postgres)")
    void theJavaAllowlistAndTheEffectiveCheckConstraintAgree() {
        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(c.oid)
                FROM pg_constraint c
                JOIN pg_class t ON t.oid = c.conrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE c.conname = 'ck_ethics_notification_event'
                  AND t.relname = 'ethics_notification_outbox'
                  AND n.nspname = 'ethics_service'
                """, String.class);
        var inConstraint = new java.util.TreeSet<String>();
        var matcher = java.util.regex.Pattern.compile("'([A-Z0-9_]+)'").matcher(definition);
        while (matcher.find()) inConstraint.add(matcher.group(1));
        assertThat(inConstraint).containsExactlyInAnyOrderElementsOf(
                com.example.ethics.notification.NotificationOutboxPublisher.allowed());

        UUID org = UUID.fromString("00000000-0000-0000-0000-0000000f1153"); // no case rows: nothing sweeps it
        for (String event : com.example.ethics.notification.NotificationOutboxPublisher.allowed()) {
            tx.executeWithoutResult(status -> notifications.enqueue(org, event, NOW));
        }
        assertThat(jdbc.queryForList("""
                SELECT event_type FROM ethics_service.ethics_notification_outbox WHERE org_id = ?
                """, String.class, org))
                .as("every allowed event was accepted by the CHECK, each exactly once")
                .containsExactlyInAnyOrderElementsOf(com.example.ethics.notification.NotificationOutboxPublisher.allowed());
        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO ethics_service.ethics_notification_outbox (id, org_id, event_type, created_at)
                VALUES (?, ?, 'CASE_ESCALATED_L6', ?)
                """, UUID.randomUUID(), org, Timestamp.from(NOW)))
                .as("the database refuses an event the Java side never writes")
                .isInstanceOf(DataAccessException.class);
    }
}

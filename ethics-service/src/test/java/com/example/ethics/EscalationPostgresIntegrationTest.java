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
        "ethics.sla.escalation.steps=PT0S,P3D",
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
     * The row lock is what makes "still unacknowledged" true at commit time. A sweep that
     * arrives while an acknowledgement transaction holds the case row must wait for it and
     * then see the acknowledgement — not decide from the snapshot it would have read first.
     */
    @Test
    @DisplayName("onay işlemi satırı tutarken gelen tarama bekler ve onayı görür")
    void aSweepArrivingDuringAnAcknowledgementWaitsAndSeesIt() throws Exception {
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-00000000e5c3");
        UUID caseId = insertCase(orgId, NOW.minus(Duration.ofDays(12)));
        var locked = new CountDownLatch(1);

        try (var pool = Executors.newSingleThreadExecutor()) {
            var acknowledging = pool.submit(() -> tx.execute(status -> {
                cases.lockById(caseId).orElseThrow();
                locked.countDown();
                try { Thread.sleep(1500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                return cases.markAcknowledged(caseId, NOW.minusSeconds(1));
            }));
            assertThat(locked.await(30, TimeUnit.SECONDS)).isTrue();

            var result = sweeper.runCycle(NOW);

            assertThat(acknowledging.get(30, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(result.failed()).isZero();
        }
        assertThat(escalations.findAllByCaseIdOrderByEscalatedAtAscLevelAsc(caseId))
                .as("kilit sırasında onaylanan vaka seviye almamalı")
                .isEmpty();
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
}

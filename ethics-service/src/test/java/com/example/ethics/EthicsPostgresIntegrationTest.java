package com.example.ethics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.ethics.api.EthicsDtos.CreateReportRequest;
import com.example.ethics.api.EthicsDtos.ReportCategory;
import com.example.ethics.api.EthicsDtos.ReportMode;
import com.example.ethics.audit.AuditOutboxWorker;
import com.example.ethics.audit.EthicsAuditIntegrityVerifier;
import com.example.ethics.model.AuditOutbox;
import com.example.ethics.repository.AuditOutboxRepository;
import com.example.ethics.repository.WormAuditRepository;
import com.example.ethics.service.EthicsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Proves Flyway and PostgreSQL advisory-lock idempotency on the production database engine. */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class EthicsPostgresIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired EthicsService service;
    @Autowired AuditOutboxRepository auditOutbox;
    @Autowired WormAuditRepository wormAudit;
    @Autowired AuditOutboxWorker auditWorker;
    @Autowired EthicsAuditIntegrityVerifier integrityVerifier;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;

    @Test
    void outboxDeliversOnceToHashChainedAppendOnlyLedgerAndCheckpoints() {
        var existingIds = new HashSet<>(auditOutbox.findAll().stream()
                .map(AuditOutbox::getId)
                .toList());
        var request = new CreateReportRequest(
                ReportMode.ANONYMOUS,
                ReportCategory.OTHER,
                "Sentetik WORM zinciri",
                "Outbox teslim ve append-only negatif testi",
                "tr",
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_worm1",
                "tr-test-pilot-v1");
        service.createReport(
                "etik.acik.com",
                "worm-delivery-" + UUID.randomUUID(),
                request);
        AuditOutbox created = auditOutbox.findAll().stream()
                .filter(row -> !existingIds.contains(row.getId()))
                .findFirst()
                .orElseThrow();

        long before = wormAudit.count();
        AuditOutboxWorker.CycleResult result = auditWorker.runCycle();
        assertThat(result.delivered()).isGreaterThanOrEqualTo(1);
        assertThat(wormAudit.count()).isGreaterThanOrEqualTo(before + 1);

        var ledger = wormAudit.findBySourceOutboxId(created.getId()).orElseThrow();
        assertThat(ledger.getEntryHash()).matches("[0-9a-f]{64}");
        assertThat(auditOutbox.findById(created.getId()).orElseThrow().getStatus())
                .isEqualTo("DELIVERED");
        assertThat(integrityVerifier.verify(
                wormAudit.findByOrgIdOrderBySeqAsc(created.getOrgId())).valid()).isTrue();

        long afterFirstDelivery = wormAudit.count();
        AuditOutboxWorker.CycleResult replayCycle = auditWorker.runCycle();
        assertThat(replayCycle.claimed()).isZero();
        assertThat(wormAudit.count()).isEqualTo(afterFirstDelivery);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ethics_worm_audit SET payload = payload WHERE seq = ?",
                ledger.getSeq()))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ethics_worm_audit WHERE seq = ?",
                ledger.getSeq()))
                .isInstanceOf(DataAccessException.class);
        assertThat(wormAudit.findBySourceOutboxId(created.getId())).isPresent();
    }

    @Test
    void auditOutboxPayloadIsValidJsonEvenWhenInputContainsQuotesAndBackslashes() throws Exception {
        // Faz 35 ES-306 residual — hand-rolled string concatenation used to
        // corrupt AuditOutbox.payload when a reporter subject/description
        // contained embedded quotes or backslashes. Switching to Jackson
        // guarantees a well-formed JSON document for downstream consumers
        // (audit-event-consumer-service, WORM archive).
        var hostileRequest = new CreateReportRequest(
                ReportMode.ANONYMOUS,
                ReportCategory.OTHER,
                "SQL\" OR 1=1;--  \\ escaped subject",
                "Body with a \"quote\" and back\\slash",
                "tr-TR",
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_hosti",
                "v1.0.0");
        service.createReport("etik.acik.com", "audit-json-safety-" + java.util.UUID.randomUUID(), hostileRequest);
        List<AuditOutbox> rows = auditOutbox.findAll();
        assertThat(rows).isNotEmpty();
        for (AuditOutbox row : rows) {
            JsonNode parsed = objectMapper.readTree(row.getPayload());
            assertThat(parsed.isObject()).isTrue();
        }
    }

    @Test
    void concurrentIntakeUsesOnePostgresCommitAndOneReceipt() throws Exception {
        var request = new CreateReportRequest(
                ReportMode.ANONYMOUS,
                ReportCategory.OTHER,
                "Sentetik PostgreSQL yarışı",
                "Aynı idempotency anahtarıyla güvenli test verisi",
                "tr",
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_abcdef",
                "tr-test-pilot-v1");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> { start.await(); return service.createReport("etik.acik.com", "pg-race-1", request); });
            var second = pool.submit(() -> { start.await(); return service.createReport("etik.acik.com", "pg-race-1", request); });
            start.countDown();
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            assertThat(results.get(0).receiptId()).isEqualTo(results.get(1).receiptId());
            assertThat(results.stream().filter(result -> result.idempotentReplay()).count()).isEqualTo(1);
        }
    }
}

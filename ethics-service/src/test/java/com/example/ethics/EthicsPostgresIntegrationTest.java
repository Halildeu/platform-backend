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
import com.example.ethics.repository.AuditScopeRepository;
import com.example.ethics.repository.WormAuditRepository;
import com.example.ethics.repository.ReporterAccessGrantRepository;
import com.example.ethics.service.EthicsService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.sql.Timestamp;
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
// ES-203/D: this class does not use the `test` profile, so the participant handle key
// has to arrive here. The service refuses to start without one by design — a default
// would mint handles from a constant, which is the same as no scoping at all.
@SpringBootTest(properties =
        "ethics.participant-handle-key=test-only-participant-handle-key-0123456789")
@Testcontainers(disabledWithoutDocker = true)
class EthicsPostgresIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired EthicsService service;
    @Autowired AuditOutboxRepository auditOutbox;
    @Autowired WormAuditRepository wormAudit;
    @Autowired AuditScopeRepository auditScope;
    @Autowired com.example.ethics.audit.AuditDeliveryService auditDelivery;
    @Autowired AuditOutboxWorker auditWorker;
    @Autowired EthicsAuditIntegrityVerifier integrityVerifier;
    @Autowired ObjectMapper objectMapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReporterAccessGrantRepository reporterGrants;

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

        // Schema-qualified, and asserted on the reason. Unqualified these two statements failed
        // with "relation does not exist" — raw JDBC does not carry Hibernate's default schema —
        // so the immutability proof was passing on a typo rather than on the trigger. Everything
        // else in this file was already qualified; these two were not.
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ethics_service.ethics_worm_audit SET payload = payload WHERE seq = ?",
                ledger.getSeq()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ethics_service.ethics_worm_audit WHERE seq = ?",
                ledger.getSeq()))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("append-only");
        assertThat(wormAudit.findBySourceOutboxId(created.getId())).isPresent();
    }

    /**
     * Faz 35 ES-302 — every ledger row is classified as it is written (#884).
     *
     * <p>V10 classified the ledger by backfill and V11 asserted the result was complete. Both
     * ran once. Nothing classified the rows written afterwards, so coverage was true at the
     * instant of the migration and would have decayed from the next case event onward —
     * invisibly, because the only assertion lives in a migration that never runs again.
     * Measured on the test cell before this change: 430 ledger rows, 430 classified, zero
     * written since. The hole was empty only because nothing had happened yet.
     *
     * <p>That decay is not repairable later. The classification is derived from the ledger
     * row's parents and erasure destroys them, so an unclassified row permanently cannot
     * answer "was every record of this case erased?" — the one question it exists for.
     *
     * <p>Asserted on the production engine because the whole delivery path is Postgres-only:
     * the claim uses {@code FOR UPDATE SKIP LOCKED} and the append takes an advisory lock.
     */
    @Test
    void everyLedgerRowIsClassifiedAsItIsAppended() {
        var request = new CreateReportRequest(
                ReportMode.ANONYMOUS,
                ReportCategory.OTHER,
                "Sinif landirma kapsami",
                "Defter satiri yazildigi anda siniflandirilmali",
                "tr",
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_scope1",
                "tr-test-pilot-v1");
        var known = new HashSet<>(auditOutbox.findAll().stream().map(AuditOutbox::getId).toList());
        service.createReport(
                "etik.acik.com",
                "audit-scope-" + UUID.randomUUID(),
                request);
        AuditOutbox enqueued = auditOutbox.findAll().stream()
                .filter(row -> !known.contains(row.getId()))
                .findFirst()
                .orElseThrow();
        UUID caseId = enqueued.getAggregateId();

        auditWorker.runCycle();

        var classification = jdbc.queryForMap("""
                SELECT s.aggregate_type, s.root_case_id, s.classified_by
                  FROM ethics_service.ethics_audit_scope s
                  JOIN ethics_service.ethics_worm_audit w ON w.id = s.worm_audit_id
                 WHERE w.aggregate_id = ?
                   AND w.event_type = 'ethics.report.created'
                """, caseId);
        assertThat(classification.get("aggregate_type")).isEqualTo("CASE");
        assertThat(classification.get("root_case_id")).hasToString(caseId.toString());
        assertThat(classification.get("classified_by")).isEqualTo(AuditScopeRepository.WRITER);

        // The invariant itself, asserted after new rows exist — the case V11 structurally
        // cannot cover, since it reports on the ledger as it stood during the migration.
        assertThat(auditScope.countUnclassifiedLedgerRows())
                .as("siniflandirilmamis defter satiri var - silme makbuzu bu satirlar icin "
                        + "artik dogrulanamaz")
                .isZero();
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

    @Test
    void evidenceDerivationLedgerRejectsUpdateAndDeleteOnPostgres() {
        var request = new CreateReportRequest(
                ReportMode.ANONYMOUS,
                ReportCategory.OTHER,
                "Sentetik evidence ledger",
                "Append-only PostgreSQL trigger testi",
                "tr",
                "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ_ledger",
                "tr-test-pilot-v1");
        var created = service.createReport(
                "etik.acik.com",
                "evidence-ledger-" + UUID.randomUUID(),
                request);
        UUID caseId = reporterGrants.findById(created.receiptId())
                .orElseThrow().getCaseId();
        UUID attachmentId = UUID.randomUUID();
        Instant now = Instant.now();
        Timestamp nowSql = Timestamp.from(now);
        Timestamp expirySql = Timestamp.from(now.plusSeconds(600));
        String sha = "a".repeat(64);

        jdbc.update("""
                INSERT INTO ethics_service.ethics_evidence_attachments (
                    id, case_id, org_id, channel, state, idempotency_key,
                    request_hash, policy_version, declared_media_type,
                    declared_size, declared_sha256, quarantine_key, sealed_key,
                    derivative_key, upload_capability_hash, upload_expires_at,
                    upload_consumed_at, sealed_version_id, sealed_sha256,
                    sealed_size, derivative_version_id, derivative_sha256,
                    derivative_size, derivative_media_type, created_at, updated_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                attachmentId, caseId,
                UUID.fromString("00000000-0000-0000-0000-000000000035"),
                "etik.acik.com", "AVAILABLE", "ledger-test",
                sha, "faz35-evidence-custody/v1", "text/plain; charset=utf-8",
                4L, sha, "quarantine/" + UUID.randomUUID(),
                "sealed/" + UUID.randomUUID(), "derivative/" + UUID.randomUUID(),
                "b".repeat(64), expirySql, nowSql,
                "sealed-v1", sha, 4L, "derivative-v1", sha, 4L,
                "text/plain; charset=utf-8", nowSql, nowSql);

        jdbc.update("""
                INSERT INTO ethics_service.ethics_evidence_derivations (
                    id, attachment_id, derivation_version, sealed_sha256,
                    sealed_size, derivative_sha256, derivative_size,
                    input_media_type, output_media_type, scanner_digest,
                    sanitizer_digest, parser_digest, rules_version,
                    policy_version, transformation_profile,
                    previous_manifest_hash, manifest_hash, signature_alg,
                    manifest_signature, created_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,
                UUID.randomUUID(), attachmentId, 1, sha, 4L, sha, 4L,
                "text/plain; charset=utf-8", "text/plain; charset=utf-8",
                "sha256:" + sha, "sha256:" + sha, "sha256:" + sha,
                "synthetic-rules-v1", "faz35-evidence-custody/v1",
                "synthetic-transform-v1", null, "c".repeat(64),
                "HMAC-SHA256", "d".repeat(64), nowSql);

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE ethics_service.ethics_evidence_derivations SET rules_version = rules_version WHERE attachment_id = ?",
                attachmentId))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM ethics_service.ethics_evidence_derivations WHERE attachment_id = ?",
                attachmentId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM ethics_service.ethics_evidence_derivations WHERE attachment_id = ?",
                Long.class, attachmentId)).isEqualTo(1L);
    }

    /**
     * ES-403 (#885) — a subscription event is classified ORG on the production engine.
     *
     * <p>The derivation lives in one SQL statement shared by every writer, so the only way to
     * know it resolves a subscription aggregate correctly is to append one and look. Left
     * unhandled it would fall through to UNRESOLVED — the label reserved for a parent that was
     * already gone — and an erasure receipt could not tell the two apart.
     */
    @Test
    void anOrgScopedLedgerRowIsClassifiedAsOrg() {
        UUID orgId = UUID.fromString("00000000-0000-0000-0000-0000000008a5");
        UUID subscriptionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO ethics_service.ethics_org_subscription
                    (id, org_id, product_id, active, granted_at)
                VALUES (?, ?, 'etik-speak-core', true, now())
                """, subscriptionId, orgId);

        UUID outboxId = UUID.randomUUID();
        UUID claim = UUID.randomUUID();
        Instant now = Instant.now();
        Instant lockedUntil = now.plusSeconds(60);
        jdbc.update("""
                INSERT INTO ethics_service.ethics_audit_outbox
                    (id, org_id, aggregate_id, event_type, payload, status, created_at,
                     attempt_count, claim_token, locked_until)
                VALUES (?, ?, ?, 'ethics.subscription.granted', '{}', 'PROCESSING', now(), 1, ?, ?)
                """, outboxId, orgId, subscriptionId, claim, Timestamp.from(lockedUntil));

        auditDelivery.deliver(outboxId, claim, lockedUntil, now);

        var classification = jdbc.queryForMap("""
                SELECT s.aggregate_type, s.root_case_id
                  FROM ethics_service.ethics_audit_scope s
                  JOIN ethics_service.ethics_worm_audit w ON w.id = s.worm_audit_id
                 WHERE w.aggregate_id = ?
                """, subscriptionId);
        assertThat(classification.get("aggregate_type")).isEqualTo("ORG");
        assertThat(classification.get("root_case_id"))
                .as("kurum kapsamli satir kok vaka tasiyor")
                .isNull();
        assertThat(auditScope.countUnclassifiedLedgerRows()).isZero();
    }
}

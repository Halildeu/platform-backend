package com.example.auditconsumer;

import com.example.auditconsumer.audit.AuditIntegrityVerifier;
import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.repository.AuditEventRepository;
import com.example.auditconsumer.service.AuditEventPersistenceService;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistOutcome;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — END-TO-END Testcontainers proof
 * (Redis + Postgres). This is the No-Fake-Work acceptance gate: it drives the
 * GENUINE producer→stream→consumer→immutable-persist chain on real engines and
 * proves the load-bearing invariants:
 *
 * <ol>
 *   <li><b>Real producer contract:</b> events are XADDed exactly as the live
 *       {@code RedisStreamAuditSink} producer emits them — {@code tenantId} /
 *       {@code userId} as NUMERIC strings (backend companyId/userId), NOT UUIDs.
 *       The consumer parses them as {@code BIGINT} and persists with no
 *       UUID-parse failure (this is the bug the must-fix closes: a UUID-parsing
 *       consumer would drop every live event).</li>
 *   <li><b>Hash-chain:</b> the persisted per-tenant chain verifies (prev→entry
 *       linkage + re-hash) and a tampered row is detected.</li>
 *   <li><b>Idempotency:</b> re-XADDing the SAME logical event yields one row.</li>
 *   <li><b>Constraint-specific dup:</b> a non-dedup UNIQUE violation is NOT
 *       swallowed as a duplicate (MUST-FIX #2) — real PG raises, the service's
 *       classifier rethrows.</li>
 *   <li><b>Poison → DLQ:</b> a malformed event is parked on the DLQ stream and
 *       only then ACKed off the source stream (MUST-FIX #3) — no audit loss.</li>
 *   <li><b>Immutability:</b> the DB-level append-only trigger rejects any direct
 *       UPDATE/DELETE.</li>
 * </ol>
 *
 * <p>The consumer loop is the real bean (default {@code audit.consumer.enabled=true}).
 * The test plays the producer by XADDing to the stream key with the EXACT
 * {@code RedisStreamAuditSink} field shape (numeric-string tenant/user), so this
 * exercises the consumer's real parse/persist path against the live contract.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditPipelineEndToEndPostgresIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("audit_event")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        // Flyway owns the schema; pin Flyway + Hibernate to `public`.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");

        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));

        // Tight poll loop so the test observes persistence quickly. Low claim
        // idle so the reclaim path is also exercised under Awaitility windows.
        registry.add("audit.consumer.enabled", () -> "true");
        registry.add("audit.consumer.poll.block-millis", () -> "250");
        registry.add("audit.consumer.poll.claim-min-idle-millis", () -> "500");
        registry.add("audit.consumer.dlq-stream-key", () -> "audit:events:dlq");
        // Eureka off in tests.
        registry.add("eureka.client.enabled", () -> "false");
    }

    private static final String STREAM_KEY = "audit:events";
    private static final String DLQ_KEY = "audit:events:dlq";
    // Numeric companyId tenant keys (producer contract — NOT UUIDs).
    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 1002L;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private AuditIntegrityVerifier verifier;

    @Autowired
    private AuditEventPersistenceService persistence;

    @Autowired
    private AuditConsumerProperties props;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager txManager;

    /**
     * Build the EXACT field map the live {@code RedisStreamAuditSink} producer
     * emits: numeric-string tenantId/userId, the same key set, same encoding.
     */
    private Map<String, String> rejectedEvent(long tenantId, String sessionId, long chunkSeq,
                                              int httpStatus, String rejectionCode,
                                              Long retryAfterSeconds, long timestampMs) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "CHUNK_ADMISSION_REJECTED");
        fields.put("sessionId", sessionId);
        fields.put("tenantId", Long.toString(tenantId));      // numeric string — live contract
        fields.put("userId", "7");                            // numeric string — live contract
        fields.put("chunkSeq", Long.toString(chunkSeq));
        fields.put("httpStatus", Integer.toString(httpStatus));
        fields.put("rejectionCode", rejectionCode);
        fields.put("retryAfterSeconds", retryAfterSeconds == null ? "" : Long.toString(retryAfterSeconds));
        fields.put("correlationId", "corr-" + sessionId + "-" + chunkSeq);
        fields.put("timestampMs", Long.toString(timestampMs));
        return fields;
    }

    private void xadd(Map<String, String> fields) {
        redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(STREAM_KEY));
    }

    private long rowCount(long tenantId) {
        return repository.findByTenantIdOrderBySeqAsc(tenantId).size();
    }

    private long dlqLen() {
        Long size = redis.opsForStream().size(DLQ_KEY);
        return size == null ? 0L : size;
    }

    @Test
    void realNumericTenantEventsFlowThroughToImmutableHashChainedPersist() {
        long base = 1_700_000_000_000L;
        xadd(rejectedEvent(TENANT_A, "sess-1", 1, 413, "OVERSIZE", null, base));
        xadd(rejectedEvent(TENANT_A, "sess-1", 2, 429, "QUEUE_FULL", 10L, base + 10));
        xadd(rejectedEvent(TENANT_A, "sess-2", 1, 503, "STT_UNAVAILABLE", 30L, base + 20));

        // 1. PIPELINE — consumer XREADGROUP + persist lands all three rows.
        //    (A UUID-parsing consumer would have dropped all three as INVALID.)
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(rowCount(TENANT_A)).isEqualTo(3));

        // Nothing was dead-lettered — these are all well-formed.
        assertThat(dlqLen()).isZero();

        List<AuditEvent> chain = repository.findByTenantIdOrderBySeqAsc(TENANT_A);
        AuditEvent first = chain.get(0);
        assertThat(first.getTenantId()).isEqualTo(TENANT_A);   // numeric tenant persisted
        assertThat(first.getEventType()).isEqualTo("CHUNK_ADMISSION_REJECTED");
        assertThat(first.getSessionId()).isEqualTo("sess-1");
        assertThat(first.getHttpStatus()).isEqualTo(413);
        assertThat(first.getRejectionCode()).isEqualTo("OVERSIZE");
        assertThat(first.getRetryAfterSeconds()).isNull(); // empty → null round-trip
        assertThat(first.getUserId()).isEqualTo(7L);
        assertThat(first.getIngestedAt()).isNotNull();

        // 2. HASH-CHAIN — genesis prev null, links chained, verifier valid.
        assertThat(first.getPrevHash()).isNull();
        assertThat(chain.get(1).getPrevHash()).isEqualTo(first.getEntryHash());
        assertThat(chain.get(2).getPrevHash()).isEqualTo(chain.get(1).getEntryHash());
        AuditIntegrityVerifier.Result ok = verifier.verifyTenant(TENANT_A);
        assertThat(ok.valid()).isTrue();
        assertThat(ok.checkedCount()).isEqualTo(3);
    }

    @Test
    void redeliveryOfSameEventIsIdempotent() {
        long ts = 1_700_000_500_000L;
        Map<String, String> event = rejectedEvent(TENANT_B, "idem-sess", 5, 429, "QUEUE_FULL", 10L, ts);

        xadd(event);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(rowCount(TENANT_B)).isEqualTo(1));

        // Re-XADD the SAME logical event (new physical stream entry id, same
        // natural dedup key) — at-least-once redelivery / producer retry.
        xadd(event);
        xadd(event);

        // The dedup_key unique constraint + existence probe collapse them: still
        // exactly one row after the consumer has had time to process the dups.
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(rowCount(TENANT_B)).isEqualTo(1));

        assertThat(verifier.verifyTenant(TENANT_B).valid()).isTrue();
        assertThat(verifier.verifyTenant(TENANT_B).checkedCount()).isEqualTo(1);
    }

    @Test
    void dedupRaceThroughServicePersistPathIsDuplicateWithoutTransactionAbort() {
        // CODEX iter-2 #2 — the load-bearing proof. Drive the SAME logical event
        // through the REAL service persist() path TWICE on real PostgreSQL (NOT a
        // direct saveAndFlush, and NOT a mock): the atomic
        // INSERT ... ON CONFLICT (dedup_key) DO NOTHING must resolve the second
        // call to an ack-able DUPLICATE *without* raising and *without* leaving
        // the transaction in PG's aborted state.
        //
        // A save()→catch(DataIntegrityViolationException)→existsByDedupKey()
        // classifier would here trip the dedup_key UNIQUE constraint, abort the
        // REQUIRES_NEW transaction, and then fail the follow-up SELECT with
        // "current transaction is aborted, commands ignored until end of
        // transaction block" — turning a benign duplicate into an exception that
        // never ACKs (PEL/retry loop). This test reproduces the genuine PG
        // semantics the unit-mock could not, and proves the atomic insert closes
        // it.
        long tenant = 1003L;
        long ts = 1_700_000_700_000L;
        Map<String, String> event = rejectedEvent(tenant, "race-sess", 1, 413, "OVERSIZE", null, ts);

        // 1st persist: a brand-new event → INSERTED.
        PersistOutcome first = persistence.persist(event, "10-0");
        assertThat(first.result()).isEqualTo(PersistResult.PERSISTED);

        // 2nd persist of the SAME logical event via the service path. To exercise
        // the racing-insert branch (not the fast-path existence probe), the
        // dedup_key already exists in the table — the ON CONFLICT DO NOTHING must
        // report 0 affected rows → DUPLICATE, with NO DataIntegrityViolation and
        // NO aborted-transaction error.
        PersistOutcome second = persistence.persist(event, "10-1");
        assertThat(second.result())
                .as("dedup race must be an ack-able DUPLICATE, not an exception")
                .isEqualTo(PersistResult.DUPLICATE);

        // Exactly one row — the duplicate did not insert, and the chain is intact.
        assertThat(rowCount(tenant)).isEqualTo(1);
        assertThat(verifier.verifyTenant(tenant).valid()).isTrue();
        assertThat(verifier.verifyTenant(tenant).checkedCount()).isEqualTo(1);

        // The transaction was NOT aborted: an ordinary read AFTER the duplicate
        // persist succeeds (an aborted tx would have poisoned subsequent SELECTs).
        assertThat(repository.existsByDedupKey("CHUNK_ADMISSION_REJECTED:race-sess:1")).isTrue();
    }

    @Test
    void nonDedupUniqueViolationOnInsertPathRaisesNotSwallowedOnRealPostgres() {
        // MUST-FIX #2 on real PG: a constraint violation that is NOT a dedup_key
        // collision must surface as DataIntegrityViolationException from the SAME
        // native insert statement persist() runs (so the service rethrows and the
        // consumer never ACKs/loses it), distinct from the dedup_key race which is
        // an ack-able DUPLICATE.
        long tenant = 1006L;
        long ts = 1_700_001_500_000L;

        // First, land one row through the real persistence service.
        PersistOutcome firstOutcome = persistence.persist(
                rejectedEvent(tenant, "nd-sess", 1, 413, "OVERSIZE", null, ts), "20-0");
        assertThat(firstOutcome.result()).isEqualTo(PersistResult.PERSISTED);
        UUID persistedId = repository.findByTenantIdOrderBySeqAsc(tenant).get(0).getId();

        // Now provoke a genuine NON-dedup UNIQUE(id) violation by calling the
        // EXACT native insert persist() uses, reusing the already-persisted UUID
        // id but a DISTINCT dedup_key. ON CONFLICT (dedup_key) does NOT cover the
        // UNIQUE(id) collision, so real PG raises — proof the insert path would
        // NOT mis-ack it as a duplicate. The @Modifying insert is run inside a
        // transaction (its own TransactionTemplate, exactly like persist()'s
        // @Transactional REQUIRES_NEW) so the failing statement is rolled back in
        // isolation and PG raises the genuine integrity violation.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Instant tsMicros = Instant.ofEpochMilli(ts).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.insertOnConflictDoNothing(
                persistedId, tenant, "CHUNK_ADMISSION_REJECTED", "other-sess",
                7L, 9L, 429, "QUEUE_FULL", null, "corr-other",
                tsMicros, "CHUNK_ADMISSION_REJECTED:other-sess:9", "20-1",
                null, "f".repeat(64), "SHA-256", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
        // The colliding dedup_key is genuinely absent → this is the rethrow path, not a DUPLICATE.
        assertThat(repository.existsByDedupKey("CHUNK_ADMISSION_REJECTED:other-sess:9")).isFalse();
        // The require-hash BEFORE-INSERT trigger still fires under ON CONFLICT: a
        // null entry_hash with a fresh dedup_key is a non-dedup violation that
        // also propagates (NOT swallowed), guarding against an unhashed row.
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> repository.insertOnConflictDoNothing(
                UUID.randomUUID(), tenant, "CHUNK_ADMISSION_REJECTED", "nohash-sess",
                7L, 1L, 413, "OVERSIZE", null, "corr-nohash",
                tsMicros, "CHUNK_ADMISSION_REJECTED:nohash-sess:1", "20-2",
                null, null, "SHA-256", 1)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void poisonEventIsParkedOnDlqThenAckedOffSourceStream() {
        // MUST-FIX #3: a malformed (poison) event — here a non-numeric tenantId,
        // exactly the kind of bad payload the old UUID-shaped test masked — must
        // be routed to the DLQ stream and only then ACKed off the source, so the
        // audit event is parked (never silently dropped) and never loops forever.
        long ts = 1_700_000_800_000L;
        Map<String, String> poison = rejectedEvent(99L, "poison-sess", 1, 413, "OVERSIZE", null, ts);
        poison.put("tenantId", "not-a-number"); // unmappable → INVALID → DLQ

        xadd(poison);

        // The poison entry lands on the DLQ stream...
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(dlqLen()).isEqualTo(1));

        // ...carrying the verbatim source fields + diagnostic envelope.
        List<MapRecord<String, Object, Object>> dlqRecords = redis.opsForStream()
                .range(DLQ_KEY, org.springframework.data.domain.Range.unbounded());
        assertThat(dlqRecords).hasSize(1);
        Map<Object, Object> dlqFields = dlqRecords.get(0).getValue();
        assertThat(dlqFields).containsEntry("sessionId", "poison-sess");
        assertThat(dlqFields).containsEntry("tenantId", "not-a-number");
        assertThat(dlqFields).containsKey("_dlqReason");
        assertThat(dlqFields).containsEntry("_dlqSourceStream", STREAM_KEY);
        assertThat(String.valueOf(dlqFields.get("_dlqReason"))).contains("tenantId");

        // ...and the source entry is ACKed (pending drains to 0), so it is not
        // redelivered in an infinite poison loop.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var summary = redis.opsForStream()
                            .pending(STREAM_KEY, props.getGroup().getName());
                    long pending = summary == null ? 0L : summary.getTotalPendingMessages();
                    assertThat(pending).isZero();
                });

        // No audit row was written for the poison event.
        assertThat(rowCount(99L)).isZero();
    }

    @Test
    void appendOnlyTriggerRejectsDirectUpdateAndDelete() {
        long ts = 1_700_000_900_000L;
        long tenant = 1004L;
        xadd(rejectedEvent(tenant, "imm-sess", 1, 413, "OVERSIZE", null, ts));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(rowCount(tenant)).isEqualTo(1));

        UUID rowId = repository.findByTenantIdOrderBySeqAsc(tenant).get(0).getId();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE audit_event SET rejection_code = 'TAMPERED' WHERE id = ?", rowId))
                .hasMessageContaining("append-only");

        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM audit_event WHERE id = ?", rowId))
                .hasMessageContaining("append-only");
    }

    @Test
    void tamperedRowIsDetectedByVerifier() {
        long ts = 1_700_001_200_000L;
        long tenant = 1005L;
        xadd(rejectedEvent(tenant, "tmp-sess", 1, 413, "OVERSIZE", null, ts));
        xadd(rejectedEvent(tenant, "tmp-sess", 2, 429, "QUEUE_FULL", 10L, ts + 10));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(rowCount(tenant)).isEqualTo(2));

        assertThat(verifier.verifyTenant(tenant).valid()).isTrue();

        UUID targetId = repository.findByTenantIdOrderBySeqAsc(tenant).get(0).getId();

        // Simulate genuine out-of-band DB tampering: bypass the append-only
        // trigger (DISABLE TRIGGER USER, the endpoint-admin pattern) and mutate a
        // stored field. The stored entry_hash no longer matches a re-hash, so the
        // verifier MUST flag it — this is exactly what the chain defends against.
        jdbc.execute("ALTER TABLE audit_event DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE audit_event SET rejection_code = 'SILENTLY_TAMPERED' WHERE id = ?", targetId);
        } finally {
            jdbc.execute("ALTER TABLE audit_event ENABLE TRIGGER USER");
        }

        AuditIntegrityVerifier.Result result = verifier.verifyTenant(tenant);
        assertThat(result.valid()).isFalse();
        assertThat(result.firstFailureEventId()).isEqualTo(targetId);
        assertThat(result.message()).contains("Tamper detected");
    }
}

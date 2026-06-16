package com.example.auditconsumer;

import com.example.auditconsumer.audit.AuditIntegrityVerifier;
import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.repository.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
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
 * GENUINE producer→stream→consumer→immutable-persist chain and proves the four
 * load-bearing invariants on real engines:
 *
 * <ol>
 *   <li><b>Pipeline:</b> synthetic ChunkAdmissionRejected events XADDed to
 *       {@code audit:events} (exactly the {@code RedisStreamAuditSink} field
 *       schema) are XREADGROUP-consumed and persisted by the live
 *       {@link com.example.auditconsumer.consumer.AuditStreamConsumer} loop.</li>
 *   <li><b>Hash-chain:</b> the persisted per-tenant chain verifies (prev→entry
 *       linkage + re-hash) and a tampered row is detected.</li>
 *   <li><b>Idempotency:</b> re-XADDing the SAME logical event (at-least-once
 *       redelivery) yields exactly one row.</li>
 *   <li><b>Immutability:</b> the DB-level append-only trigger rejects any direct
 *       UPDATE/DELETE.</li>
 * </ol>
 *
 * <p>The consumer loop is the real bean (default {@code audit.consumer.enabled=true}).
 * The test plays the producer by XADDing directly to the stream key — identical
 * payload shape to {@code RedisStreamAuditSink}, so this exercises the consumer's
 * real parse/persist path.
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
        // Eureka off in tests.
        registry.add("eureka.client.enabled", () -> "false");
    }

    private static final String STREAM_KEY = "audit:events";
    private static final UUID TENANT_A = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID TENANT_B = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private AuditIntegrityVerifier verifier;

    @Autowired
    private JdbcTemplate jdbc;

    /** Build the exact field map the {@code RedisStreamAuditSink} producer emits. */
    private Map<String, String> rejectedEvent(UUID tenantId, String sessionId, long chunkSeq,
                                              int httpStatus, String rejectionCode,
                                              Long retryAfterSeconds, long timestampMs) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "CHUNK_ADMISSION_REJECTED");
        fields.put("sessionId", sessionId);
        fields.put("tenantId", tenantId.toString());
        fields.put("userId", "7");
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

    private long rowCount(UUID tenantId) {
        return repository.findByTenantIdOrderBySeqAsc(tenantId).size();
    }

    @Test
    void synthEventFlowsThroughToImmutableHashChainedPersist() {
        long base = 1_700_000_000_000L;
        xadd(rejectedEvent(TENANT_A, "sess-1", 1, 413, "OVERSIZE", null, base));
        xadd(rejectedEvent(TENANT_A, "sess-1", 2, 429, "QUEUE_FULL", 10L, base + 10));
        xadd(rejectedEvent(TENANT_A, "sess-2", 1, 503, "STT_UNAVAILABLE", 30L, base + 20));

        // 1. PIPELINE — consumer XREADGROUP + persist lands all three rows.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(rowCount(TENANT_A)).isEqualTo(3));

        List<AuditEvent> chain = repository.findByTenantIdOrderBySeqAsc(TENANT_A);
        // Field mapping fidelity (non-PII fields only; null-safe).
        AuditEvent first = chain.get(0);
        assertThat(first.getEventType()).isEqualTo("CHUNK_ADMISSION_REJECTED");
        assertThat(first.getSessionId()).isEqualTo("sess-1");
        assertThat(first.getHttpStatus()).isEqualTo(413);
        assertThat(first.getRejectionCode()).isEqualTo("OVERSIZE");
        assertThat(first.getRetryAfterSeconds()).isNull(); // empty → null round-trip
        assertThat(first.getUserId()).isEqualTo(7L);
        // org_id compat trigger backfilled org_id = tenant_id.
        assertThat(first.getOrgId()).isEqualTo(TENANT_A);
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
    void appendOnlyTriggerRejectsDirectUpdateAndDelete() {
        long ts = 1_700_000_900_000L;
        UUID tenant = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");
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
        UUID tenant = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd");
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

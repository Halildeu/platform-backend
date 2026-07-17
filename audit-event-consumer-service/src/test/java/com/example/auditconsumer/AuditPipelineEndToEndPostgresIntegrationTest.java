package com.example.auditconsumer;

import com.example.auditconsumer.audit.AuditIntegrityVerifier;
import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.events.ConsentEventOutboxPoller;
import com.example.auditconsumer.model.AuditEvent;
import com.example.auditconsumer.model.ConsentEventOutbox;
import com.example.auditconsumer.model.ConsentEventOutboxStatus;
import com.example.auditconsumer.model.RecordingConsentGrant;
import com.example.auditconsumer.repository.AuditEventRepository;
import com.example.auditconsumer.repository.ConsentEventOutboxRepository;
import com.example.auditconsumer.repository.RecordingConsentGrantRepository;
import com.example.auditconsumer.repository.RecordingConsentRevocationRepository;
import com.example.auditconsumer.service.AuditEventPersistenceService;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistOutcome;
import com.example.auditconsumer.service.AuditEventPersistenceService.PersistResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
        registry.add("audit.consumer.poll.dependency-max-delivery-attempts", () -> "4");
        registry.add("audit.consumer.dlq-stream-key", () -> "audit:events:dlq");
        registry.add("audit.consumer.dlq-max-len", () -> "100");
        registry.add("audit.consumer.dlq-ttl-seconds", () -> "3600");
        registry.add("audit.consent-events.redis.enabled", () -> "true");
        registry.add("audit.consent-events.redis.stream-key", () -> "meeting:events:test");
        registry.add("audit.consent-events.outbox.poller.enabled", () -> "true");
        registry.add("audit.consent-events.outbox.poll-delay-ms", () -> "100");
        registry.add("audit.consent-events.outbox.scheduling-enabled", () -> "false");
        // Eureka off in tests.
        registry.add("eureka.client.enabled", () -> "false");
    }

    private static final String STREAM_KEY = "audit:events";
    private static final String DLQ_KEY = "audit:events:dlq";
    private static final String MEETING_EVENT_STREAM_KEY = "meeting:events:test";
    // Numeric companyId tenant keys (producer contract — NOT UUIDs).
    private static final long TENANT_A = 1001L;
    private static final long TENANT_B = 1002L;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private AuditEventRepository repository;

    @Autowired
    private RecordingConsentGrantRepository grantRepository;

    @Autowired
    private RecordingConsentRevocationRepository revocationRepository;

    @Autowired
    private ConsentEventOutboxRepository outboxRepository;

    @Autowired
    private ConsentEventOutboxPoller consentEventOutboxPoller;

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

    private Map<String, String> revokedEvent(
            long tenantId,
            UUID meetingId,
            UUID captureId,
            long revision) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "RECORDING_CONSENT_REVOKED");
        fields.put("meetingId", meetingId.toString());
        fields.put("captureId", captureId.toString());
        fields.put("tenantId", Long.toString(tenantId));
        fields.put("userId", "7");
        fields.put("canonicalTenantId", "33333333-3333-3333-3333-333333333333");
        fields.put("orgId", "44444444-4444-4444-4444-444444444444");
        fields.put("subjectId", "user:7");
        fields.put("consentVersion", "v1");
        fields.put("consentRevision", Long.toString(revision));
        fields.put("reasonCode", "USER_WITHDREW");
        fields.put("correlationId", "corr-revoked-" + revision);
        fields.put("revokedAtMs", "1700002000000");
        fields.put("timestampMs", "1700002000000");
        return fields;
    }

    private Map<String, String> grantedEvent(
            long tenantId,
            UUID meetingId,
            UUID captureId) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", "RECORDING_CONSENT_GRANTED");
        fields.put("meetingId", meetingId.toString());
        fields.put("captureId", captureId.toString());
        fields.put("tenantId", Long.toString(tenantId));
        fields.put("userId", "7");
        fields.put("canonicalTenantId", "33333333-3333-3333-3333-333333333333");
        fields.put("orgId", "44444444-4444-4444-4444-444444444444");
        fields.put("subjectId", "user:7");
        fields.put("consentVersion", "v1");
        fields.put("consentTextHash", "sha256:" + "a".repeat(64));
        fields.put("locale", "tr-TR");
        fields.put("correlationId", "corr-granted");
        fields.put("timestampMs", "1700001000000");
        return fields;
    }

    private Map<String, String> legacyGrantedEvent(
            long tenantId,
            UUID meetingId,
            UUID captureId) {
        Map<String, String> fields = grantedEvent(tenantId, meetingId, captureId);
        fields.remove("canonicalTenantId");
        fields.remove("orgId");
        fields.remove("timestampMs");
        fields.put("acceptedAtMs", "1700001000000");
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

    private long pendingCount() {
        var summary = redis.opsForStream().pending(STREAM_KEY, props.getGroup().getName());
        return summary == null ? 0L : summary.getTotalPendingMessages();
    }

    private long sourceStreamLen() {
        Long size = redis.opsForStream().size(STREAM_KEY);
        return size == null ? 0L : size;
    }

    private ConsentEventOutbox persistConsentOutbox(
            long tenant,
            UUID meetingId,
            UUID captureId,
            String streamPrefix) {
        PersistOutcome grant = persistence.persist(
                grantedEvent(tenant, meetingId, captureId), streamPrefix + "-0");
        assertThat(grant.result()).isEqualTo(PersistResult.PERSISTED);
        PersistOutcome revoke = persistence.persist(
                revokedEvent(tenant, meetingId, captureId, 2L), streamPrefix + "-1");
        assertThat(revoke.result()).isEqualTo(PersistResult.PERSISTED);
        String eventKey = "meeting.consent|" + captureId + "|meeting.consent.revoked|2";
        return outboxRepository.findByEventKey(eventKey).orElseThrow();
    }

    @Test
    void realNumericTenantEventsFlowThroughToImmutableHashChainedPersist() {
        long initialDlq = dlqLen();
        long base = 1_700_000_000_000L;
        xadd(rejectedEvent(TENANT_A, "sess-1", 1, 413, "OVERSIZE", null, base));
        xadd(rejectedEvent(TENANT_A, "sess-1", 2, 429, "QUEUE_FULL", 10L, base + 10));
        xadd(rejectedEvent(TENANT_A, "sess-2", 1, 503, "STT_UNAVAILABLE", 30L, base + 20));

        // 1. PIPELINE — consumer XREADGROUP + persist lands all three rows.
        //    (A UUID-parsing consumer would have dropped all three as INVALID.)
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(rowCount(TENANT_A)).isEqualTo(3);
                    assertThat(pendingCount()).isZero();
                    assertThat(sourceStreamLen()).isZero();
                });

        // Nothing was dead-lettered — these are all well-formed.
        assertThat(dlqLen()).isEqualTo(initialDlq);

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
    void consentRevocationCommitsAuditProjectionOutboxAndRedisEventExactlyOnce() {
        long tenant = 1007L;
        UUID meetingId = UUID.fromString("55555555-5555-4555-8555-555555555555");
        UUID captureId = UUID.fromString("66666666-6666-4666-8666-666666666666");
        String eventKey = "meeting.consent|" + captureId + "|meeting.consent.revoked|2";
        Map<String, String> event = revokedEvent(tenant, meetingId, captureId, 2);
        long initialPublished = redis.opsForStream().size(MEETING_EVENT_STREAM_KEY) == null
                ? 0L : redis.opsForStream().size(MEETING_EVENT_STREAM_KEY);

        xadd(grantedEvent(tenant, meetingId, captureId));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(grantRepository.findByCaptureId(captureId)).isPresent());
        xadd(event);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(rowCount(tenant)).isEqualTo(2);
                    assertThat(revocationRepository.findByEventKey(eventKey)).isPresent();
                    assertThat(outboxRepository.findByEventKey(eventKey))
                            .get().extracting(ConsentEventOutbox::getStatus)
                            .isEqualTo(ConsentEventOutboxStatus.PENDING);
                });

        consentEventOutboxPoller.runCycle();
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100))
                .untilAsserted(() -> {
                    assertThat(outboxRepository.findByEventKey(eventKey))
                            .get().extracting(ConsentEventOutbox::getStatus)
                            .isEqualTo(ConsentEventOutboxStatus.PUBLISHED);
                    assertThat(redis.opsForStream().size(MEETING_EVENT_STREAM_KEY))
                            .isGreaterThan(initialPublished);
                });

        var projection = revocationRepository.findByEventKey(eventKey).orElseThrow();
        var outbox = outboxRepository.findByEventKey(eventKey).orElseThrow();
        assertThat(projection.getMeetingId()).isEqualTo(meetingId);
        assertThat(projection.getCaptureId()).isEqualTo(captureId);
        assertThat(projection.getConsentRevision()).isEqualTo(2);
        assertThat(projection.getSourceHash()).matches("[0-9a-f]{64}");
        assertThat(outbox.getPayload())
                .contains("\"eventType\":\"meeting.consent.revoked\"")
                .contains("\"captureId\":\"" + captureId + "\"")
                .doesNotContain("user:7")
                .doesNotContain("transcript")
                .doesNotContain("audio");

        List<MapRecord<String, Object, Object>> published = redis.opsForStream()
                .range(MEETING_EVENT_STREAM_KEY, org.springframework.data.domain.Range.unbounded());
        assertThat(published.stream()
                .filter(record -> eventKey.equals(record.getValue().get("eventKey")))
                .count()).isEqualTo(1L);

        xadd(event);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(rowCount(tenant)).isEqualTo(2);
                    assertThat(revocationRepository.findByEventKey(eventKey)).isPresent();
                    assertThat(outboxRepository.findByEventKey(eventKey)).isPresent();
                    List<MapRecord<String, Object, Object>> events = redis.opsForStream()
                            .range(MEETING_EVENT_STREAM_KEY,
                                    org.springframework.data.domain.Range.unbounded());
                    assertThat(events.stream()
                            .filter(record -> eventKey.equals(record.getValue().get("eventKey")))
                            .count()).isEqualTo(1L);
                });

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE recording_consent_revocation SET reason_code = 'TAMPERED' WHERE event_key = ?",
                eventKey)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM recording_consent_revocation WHERE event_key = ?", eventKey))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE recording_consent_grant SET actor_user_id = 99 WHERE capture_id = ?",
                captureId)).hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM recording_consent_grant WHERE capture_id = ?", captureId))
                .hasMessageContaining("append-only");
        assertThat(verifier.verifyTenant(tenant).valid()).isTrue();
        assertThat(verifier.verifyTenant(tenant).checkedCount()).isEqualTo(2);
    }

    @Test
    void consentRevocationWithDifferentActorIsDlqParkedAndConsumerContinues() {
        long tenant = 1008L;
        UUID meetingId = UUID.fromString("77777777-7777-4777-8777-777777777777");
        UUID captureId = UUID.fromString("88888888-8888-4888-8888-888888888888");
        long initialDlq = dlqLen();

        xadd(grantedEvent(tenant, meetingId, captureId));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(grantRepository.findByCaptureId(captureId)).isPresent());

        Map<String, String> forged = revokedEvent(tenant, meetingId, captureId, 2);
        forged.put("subjectId", "user:99");
        xadd(forged);
        xadd(rejectedEvent(tenant, "after-consent-conflict", 1, 429, "QUEUE_FULL", 1L,
                1_700_003_000_000L));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(dlqLen()).isEqualTo(initialDlq + 1);
                    assertThat(repository.existsByDedupKey(
                            "CHUNK_ADMISSION_REJECTED:after-consent-conflict:1")).isTrue();
                    assertThat(revocationRepository.findByEventKey(
                            "meeting.consent|" + captureId + "|meeting.consent.revoked|2"))
                            .isEmpty();
                });
    }

    @Test
    void consentUuidCaseIsCanonicalizedForDedupAndProjectionIdentity() {
        long tenant = 1011L;
        UUID meetingId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        UUID captureId = UUID.fromString("eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee");
        Map<String, String> upper = grantedEvent(tenant, meetingId, captureId);
        upper.put("meetingId", meetingId.toString().toUpperCase(java.util.Locale.ROOT));
        upper.put("captureId", captureId.toString().toUpperCase(java.util.Locale.ROOT));

        xadd(upper);
        xadd(grantedEvent(tenant, meetingId, captureId));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(grantRepository.findByCaptureId(captureId)).isPresent();
                    assertThat(rowCount(tenant)).isEqualTo(1);
                    assertThat(pendingCount()).isZero();
                });
        assertThat(repository.existsByDedupKey(
                "RECORDING_CONSENT_GRANTED:" + captureId)).isTrue();
    }

    @Test
    void revokeBeforeGrantRetriesThenPersistsAfterPredecessorArrives() {
        long tenant = 1012L;
        UUID meetingId = UUID.fromString("f1111111-1111-4111-8111-111111111111");
        UUID captureId = UUID.fromString("f2222222-2222-4222-8222-222222222222");
        long initialDlq = dlqLen();
        String eventKey = "meeting.consent|" + captureId + "|meeting.consent.revoked|2";

        xadd(revokedEvent(tenant, meetingId, captureId, 2));
        xadd(grantedEvent(tenant, meetingId, captureId));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(grantRepository.findByCaptureId(captureId)).isPresent();
                    assertThat(revocationRepository.findByEventKey(eventKey)).isPresent();
                    assertThat(outboxRepository.findByEventKey(eventKey)).isPresent();
                    assertThat(pendingCount()).isZero();
                });
        assertThat(dlqLen()).isEqualTo(initialDlq);
    }

    @Test
    void missingGrantIsBoundedlyRetriedThenParkedWithoutBlockingFollowingEvent() {
        long tenant = 1013L;
        UUID meetingId = UUID.fromString("f3333333-3333-4333-8333-333333333333");
        UUID captureId = UUID.fromString("f4444444-4444-4444-8444-444444444444");
        long initialDlq = dlqLen();

        xadd(revokedEvent(tenant, meetingId, captureId, 2));
        xadd(rejectedEvent(tenant, "after-missing-grant", 1, 429, "QUEUE_FULL", 1L,
                1_700_004_000_000L));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(dlqLen()).isEqualTo(initialDlq + 1);
                    assertThat(pendingCount()).isZero();
                    assertThat(repository.existsByDedupKey(
                            "CHUNK_ADMISSION_REJECTED:after-missing-grant:1")).isTrue();
                });
    }

    @Test
    void retainedLegacyGrantWithoutCanonicalScopeReplaysThenAuthorizesScopedRevoke() {
        long tenant = 1014L;
        UUID meetingId = UUID.fromString("f5555555-5555-4555-8555-555555555555");
        UUID captureId = UUID.fromString("f6666666-6666-4666-8666-666666666666");
        String eventKey = "meeting.consent|" + captureId + "|meeting.consent.revoked|2";

        xadd(legacyGrantedEvent(tenant, meetingId, captureId));
        xadd(revokedEvent(tenant, meetingId, captureId, 2));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    RecordingConsentGrant grant = grantRepository.findByCaptureId(captureId).orElseThrow();
                    assertThat(grant.getTenantId()).isNull();
                    assertThat(grant.getOrgId()).isNull();
                    assertThat(revocationRepository.findByEventKey(eventKey)).isPresent();
                    assertThat(outboxRepository.findByEventKey(eventKey)).isPresent();
                    assertThat(pendingCount()).isZero();
                });
    }

    @Test
    void revocationAndAuditRollBackWhenOutboxInsertFails() {
        long tenant = 1015L;
        UUID meetingId = UUID.fromString("f7777777-7777-4777-8777-777777777777");
        UUID captureId = UUID.fromString("f8888888-8888-4888-8888-888888888888");
        String eventKey = "meeting.consent|" + captureId + "|meeting.consent.revoked|2";
        assertThat(persistence.persist(grantedEvent(tenant, meetingId, captureId), "rollback-0").result())
                .isEqualTo(PersistResult.PERSISTED);

        jdbc.execute("CREATE OR REPLACE FUNCTION test_fail_consent_outbox_insert() RETURNS TRIGGER AS $$ "
                + "BEGIN RAISE EXCEPTION 'forced outbox failure'; END; $$ LANGUAGE plpgsql");
        jdbc.execute("CREATE TRIGGER trg_test_fail_consent_outbox BEFORE INSERT ON consent_event_outbox "
                + "FOR EACH ROW EXECUTE FUNCTION test_fail_consent_outbox_insert()");
        try {
            assertThatThrownBy(() -> persistence.persist(
                    revokedEvent(tenant, meetingId, captureId, 2), "rollback-1"))
                    .isInstanceOf(DataAccessException.class);
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS trg_test_fail_consent_outbox ON consent_event_outbox");
            jdbc.execute("DROP FUNCTION IF EXISTS test_fail_consent_outbox_insert()");
        }

        assertThat(rowCount(tenant)).isEqualTo(1);
        assertThat(revocationRepository.findByEventKey(eventKey)).isEmpty();
        assertThat(outboxRepository.findByEventKey(eventKey)).isEmpty();
    }

    @Test
    void realProjectionConflictIsDlqAckedAndDoesNotBlockFollowingEvent() {
        long tenant = 1016L;
        UUID meetingId = UUID.fromString("f9999999-9999-4999-8999-999999999999");
        UUID captureId = UUID.fromString("faaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        String eventKey = "meeting.consent|" + captureId + "|meeting.consent.revoked|2";
        Map<String, String> revoke = revokedEvent(tenant, meetingId, captureId, 2);
        xadd(grantedEvent(tenant, meetingId, captureId));
        xadd(revoke);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(revocationRepository.findByEventKey(eventKey)).isPresent());

        jdbc.execute("ALTER TABLE recording_consent_revocation DISABLE TRIGGER USER");
        try {
            jdbc.update("UPDATE recording_consent_revocation SET source_hash = ? WHERE event_key = ?",
                    "0".repeat(64), eventKey);
        } finally {
            jdbc.execute("ALTER TABLE recording_consent_revocation ENABLE TRIGGER USER");
        }
        long initialDlq = dlqLen();
        xadd(revoke);
        xadd(rejectedEvent(tenant, "after-real-conflict", 1, 429, "QUEUE_FULL", 1L,
                1_700_005_000_000L));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(dlqLen()).isEqualTo(initialDlq + 1);
                    assertThat(pendingCount()).isZero();
                    assertThat(repository.existsByDedupKey(
                            "CHUNK_ADMISSION_REJECTED:after-real-conflict:1")).isTrue();
                });
    }

    @Test
    void consentOutboxPayloadAndRoutingAreDatabaseImmutable() {
        ConsentEventOutbox row = persistConsentOutbox(
                1009L,
                UUID.fromString("99999999-9999-4999-8999-999999999999"),
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "901");

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE consent_event_outbox SET payload = '{}' WHERE id = ?", row.getId()))
                .hasMessageContaining("immutable payload/routing");
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE consent_event_outbox SET tenant_id = ? WHERE id = ?",
                UUID.randomUUID(), row.getId()))
                .hasMessageContaining("immutable payload/routing");
        assertThatThrownBy(() -> jdbc.update(
                "DELETE FROM consent_event_outbox WHERE id = ?", row.getId()))
                .hasMessageContaining("delete rejected");
    }

    @Test
    void consentOutboxLeaseRecoveryEndsAtAttemptLimitThenControlledRedriveCanPublish() {
        ConsentEventOutbox row = persistConsentOutbox(
                1010L,
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                "902");
        TransactionTemplate tx = new TransactionTemplate(txManager);
        Instant firstClaimAt = Instant.now()
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        UUID firstToken = UUID.randomUUID();

        int firstClaim = tx.execute(status -> outboxRepository.claimBatch(
                firstClaimAt, firstClaimAt.plusMillis(10), "worker-a", firstToken, 100));
        assertThat(firstClaim).isPositive();
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getClaimToken())
                .isEqualTo(firstToken);
        Integer stalePublish = tx.execute(status -> outboxRepository.markPublishedFenced(
                row.getId(), UUID.randomUUID(), firstClaimAt.plusMillis(1)));
        assertThat(stalePublish).isZero();

        Instant firstRecoveryAt = firstClaimAt.plusMillis(20);
        Instant retryAt = firstRecoveryAt.plusSeconds(5);
        Integer firstRecovery = tx.execute(status -> outboxRepository.recoverStaleLeases(
                firstRecoveryAt, retryAt, 2));
        assertThat(firstRecovery).isPositive();
        ConsentEventOutbox afterFirstRecovery = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(afterFirstRecovery.getStatus()).isEqualTo(ConsentEventOutboxStatus.PENDING);
        assertThat(afterFirstRecovery.getAttempts()).isEqualTo(1);
        assertThat(afterFirstRecovery.getNextAttemptAt()).isEqualTo(retryAt);

        Integer tooEarly = tx.execute(status -> outboxRepository.claimBatch(
                retryAt.minusMillis(1), retryAt.plusSeconds(1), "too-early",
                UUID.randomUUID(), 100));
        assertThat(tooEarly).isZero();

        UUID secondToken = UUID.randomUUID();
        Instant secondClaimAt = retryAt.plusMillis(1);
        Integer secondClaim = tx.execute(status -> outboxRepository.claimBatch(
                secondClaimAt, secondClaimAt.plusMillis(10), "worker-b", secondToken, 100));
        assertThat(secondClaim).isPositive();
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getClaimToken())
                .isEqualTo(secondToken);
        Instant secondRecoveryAt = secondClaimAt.plusMillis(20);
        Integer secondRecovery = tx.execute(status -> outboxRepository.recoverStaleLeases(
                secondRecoveryAt, secondRecoveryAt.plusSeconds(5), 2));
        assertThat(secondRecovery).isPositive();

        ConsentEventOutbox dead = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(dead.getStatus()).isEqualTo(ConsentEventOutboxStatus.DEAD);
        assertThat(dead.getAttempts()).isEqualTo(2);
        assertThat(dead.getLastError()).isEqualTo("LEASE_EXPIRED");
        Integer latePublish = tx.execute(status -> outboxRepository.markPublishedFenced(
                row.getId(), secondToken, secondRecoveryAt.plusMillis(1)));
        assertThat(latePublish).isZero();

        assertThatThrownBy(() -> jdbc.update(
                "UPDATE consent_event_outbox SET status = 'PENDING', next_attempt_at = now() "
                        + "WHERE id = ?", row.getId()))
                .hasMessageContaining("requires controlled redrive");

        Integer redriven = jdbc.queryForObject(
                "SELECT consent_event_outbox_redrive(?, ?)",
                Integer.class, row.getEventKey(), "operator approved");
        assertThat(redriven).isOne();
        ConsentEventOutbox pendingAgain = outboxRepository.findById(row.getId()).orElseThrow();
        assertThat(pendingAgain.getStatus()).isEqualTo(ConsentEventOutboxStatus.PENDING);
        assertThat(pendingAgain.getAttempts()).isEqualTo(2);
        assertThat(pendingAgain.getLastError()).isEqualTo("MANUAL_REDRIVE:operatorapproved");

        UUID redriveToken = UUID.randomUUID();
        Instant redriveClaimAt = Instant.now().plusMillis(1);
        Integer redriveClaim = tx.execute(status -> outboxRepository.claimBatch(
                redriveClaimAt, redriveClaimAt.plusSeconds(1), "worker-redrive",
                redriveToken, 100));
        assertThat(redriveClaim).isPositive();
        Integer redrivePublished = tx.execute(status -> outboxRepository.markPublishedFenced(
                row.getId(), redriveToken, redriveClaimAt.plusMillis(1)));
        assertThat(redrivePublished).isOne();
        assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus())
                .isEqualTo(ConsentEventOutboxStatus.PUBLISHED);
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
    void concurrentServicePersistsProduceOneRowAndOneDuplicate() throws Exception {
        long tenant = 1017L;
        Map<String, String> event = rejectedEvent(
                tenant, "concurrent-race-sess", 1, 413, "OVERSIZE", null,
                1_700_006_000_000L);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PersistOutcome> first = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return persistence.persist(new LinkedHashMap<>(event), "concurrent-1");
            });
            Future<PersistOutcome> second = executor.submit(() -> {
                ready.countDown();
                assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                return persistence.persist(new LinkedHashMap<>(event), "concurrent-2");
            });

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            List<PersistResult> results = List.of(
                    first.get(15, TimeUnit.SECONDS).result(),
                    second.get(15, TimeUnit.SECONDS).result());

            assertThat(results).containsExactlyInAnyOrder(
                    PersistResult.PERSISTED, PersistResult.DUPLICATE);
            assertThat(rowCount(tenant)).isEqualTo(1);
            assertThat(verifier.verifyTenant(tenant).valid()).isTrue();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void concurrentOutboxWorkersCannotClaimTheSameRow() throws Exception {
        drainPendingOutbox();
        ConsentEventOutbox row = persistConsentOutbox(
                1018L,
                UUID.fromString("ab111111-1111-4111-8111-111111111111"),
                UUID.fromString("ab222222-2222-4222-8222-222222222222"),
                "concurrent-claim");
        Instant now = Instant.now().plusMillis(1);
        UUID firstToken = UUID.randomUUID();
        UUID secondToken = UUID.randomUUID();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch queried = new CountDownLatch(2);
        CountDownLatch releaseWinner = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Integer> first = executor.submit(() -> concurrentClaim(
                    now, "worker-concurrent-a", firstToken, ready, start, queried, releaseWinner));
            Future<Integer> second = executor.submit(() -> concurrentClaim(
                    now, "worker-concurrent-b", secondToken, ready, start, queried, releaseWinner));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(queried.await(10, TimeUnit.SECONDS)).isTrue();
            releaseWinner.countDown();

            int firstClaimed = first.get(15, TimeUnit.SECONDS);
            int secondClaimed = second.get(15, TimeUnit.SECONDS);
            assertThat(firstClaimed + secondClaimed).isEqualTo(1);
            assertThat(outboxRepository.findByClaimToken(firstToken).size()
                    + outboxRepository.findByClaimToken(secondToken).size()).isEqualTo(1);
            assertThat(outboxRepository.findById(row.getId()).orElseThrow().getStatus())
                    .isEqualTo(ConsentEventOutboxStatus.CLAIMED);
        } finally {
            releaseWinner.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void drainPendingOutbox() {
        for (int cycle = 0;
                cycle < 10 && outboxRepository.countByStatus(ConsentEventOutboxStatus.PENDING) > 0;
                cycle++) {
            consentEventOutboxPoller.runCycle();
        }
        assertThat(outboxRepository.countByStatus(ConsentEventOutboxStatus.PENDING)).isZero();
    }

    private int concurrentClaim(
            Instant now,
            String owner,
            UUID claimToken,
            CountDownLatch ready,
            CountDownLatch start,
            CountDownLatch queried,
            CountDownLatch releaseWinner) {
        TransactionTemplate transaction = new TransactionTemplate(txManager);
        Integer claimed = transaction.execute(status -> {
            ready.countDown();
            awaitLatch(start);
            int count = outboxRepository.claimBatch(
                    now, now.plusSeconds(30), owner, claimToken, 1);
            queried.countDown();
            if (count > 0) {
                awaitLatch(releaseWinner);
            }
            return count;
        });
        return claimed == null ? 0 : claimed;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Timed out waiting for concurrent test barrier");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent test interrupted", ex);
        }
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
        long initialDlq = dlqLen();
        Map<String, String> poison = rejectedEvent(99L, "poison-sess", 1, 413, "OVERSIZE", null, ts);
        poison.put("tenantId", "not-a-number"); // unmappable → INVALID → DLQ

        xadd(poison);

        // The poison entry lands on the DLQ stream...
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(dlqLen()).isEqualTo(initialDlq + 1));

        // ...carrying only a fingerprinted diagnostic envelope, never raw identity fields.
        List<MapRecord<String, Object, Object>> dlqRecords = redis.opsForStream()
                .range(DLQ_KEY, org.springframework.data.domain.Range.unbounded());
        Map<Object, Object> dlqFields = dlqRecords.stream()
                .map(MapRecord::getValue)
                .filter(fields -> "INVALID_EVENT".equals(fields.get("_dlqReason")))
                .reduce((first, second) -> second)
                .orElseThrow();
        assertThat(dlqFields).doesNotContainKeys("sessionId", "tenantId", "userId", "subjectId");
        assertThat(dlqFields).containsEntry("_dlqReason", "INVALID_EVENT");
        assertThat(dlqFields).containsEntry("_dlqSourceStream", STREAM_KEY);
        assertThat(String.valueOf(dlqFields.get("_dlqFingerprint"))).matches("[0-9a-f]{64}");
        assertThat(redis.getExpire(DLQ_KEY, java.util.concurrent.TimeUnit.SECONDS)).isPositive();

        // ...and the source entry is atomically ACKed + deleted, so it is not
        // redelivered and the transient source stream does not grow forever.
        await().atMost(Duration.ofSeconds(15)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    var summary = redis.opsForStream()
                            .pending(STREAM_KEY, props.getGroup().getName());
                    long pending = summary == null ? 0L : summary.getTotalPendingMessages();
                    assertThat(pending).isZero();
                    assertThat(sourceStreamLen()).isZero();
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

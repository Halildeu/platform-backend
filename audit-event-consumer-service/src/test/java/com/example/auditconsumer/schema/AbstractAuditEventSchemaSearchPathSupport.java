package com.example.auditconsumer.schema;

import com.example.auditconsumer.audit.AuditIntegrityVerifier;
import com.example.auditconsumer.config.AuditConsumerProperties;
import com.example.auditconsumer.repository.AuditEventRepository;
import com.example.auditconsumer.service.AuditEventPersistenceService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.stream.StreamRecords;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249 / gitops PR #1648) — shared Testcontainers
 * support for the <b>non-public-schema search_path</b> integration tests.
 *
 * <p>The consumer persist path is a NATIVE {@code INSERT INTO audit_event}
 * (unqualified) in {@link AuditEventRepository}. In production the table lives in
 * a NON-public schema {@code audit_event} (Flyway {@code default-schema=audit_event}
 * from {@code AUDIT_CONSUMER_DB_SCHEMA}). {@code hibernate.default_schema} rewrites
 * only Hibernate-GENERATED SQL, NOT native SQL, so the connection
 * {@code search_path} alone decides whether the unqualified {@code audit_event}
 * resolves. The two concrete tests pin Flyway to {@code audit_event} (the real
 * production layout) and differ ONLY in the runtime datasource URL:
 *
 * <ul>
 *   <li>{@link AuditEventSchemaSearchPathBugReproductionPostgresIntegrationTest}
 *       — datasource WITHOUT {@code currentSchema} → search_path {@code public} →
 *       the bug (relation-not-found, event stuck unacked in the Redis PEL);</li>
 *   <li>{@link AuditEventSchemaSearchPathFixPostgresIntegrationTest}
 *       — datasource WITH {@code ?currentSchema=audit_event} (gitops PR #1648) →
 *       the event is consumed and persisted into {@code audit_event.audit_event}
 *       with the hash-chain columns.</li>
 * </ul>
 *
 * <p>Each concrete class owns its own Postgres + Redis containers (hermetic — no
 * shared mutable state, no consumer cross-talk between the two contexts). This
 * base only carries the autowired beans, the shared (datasource-URL-free) property
 * registrations, and the producer-contract helpers.
 */
abstract class AbstractAuditEventSchemaSearchPathSupport {

    /** Producer stream key (audio-gateway {@code RedisStreamAuditSink}). */
    protected static final String STREAM_KEY = "audit:events";
    /** The only producer event type today. */
    protected static final String EVENT_TYPE = "CHUNK_ADMISSION_REJECTED";
    /** The NON-public schema the table lives in (production layout). */
    protected static final String AUDIT_SCHEMA = "audit_event";

    @Autowired
    protected StringRedisTemplate redis;

    @Autowired
    protected AuditEventRepository repository;

    @Autowired
    protected AuditIntegrityVerifier verifier;

    @Autowired
    protected AuditEventPersistenceService persistence;

    @Autowired
    protected AuditConsumerProperties props;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected PlatformTransactionManager txManager;

    /**
     * Register everything EXCEPT {@code spring.datasource.url} — each concrete
     * class adds its own URL (with / without {@code currentSchema}). Crucially,
     * Flyway is pinned to the NON-public {@code audit_event} schema so the table
     * is created OFF the default {@code public} search_path — this is what makes
     * the search_path semantics observable (and is the layout the original test
     * skipped by pinning everything to {@code public}).
     */
    protected static void registerCommonProperties(DynamicPropertyRegistry registry,
                                                   PostgreSQLContainer<?> postgres,
                                                   GenericContainer<?> redisContainer) {
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");

        // PRODUCTION schema layout: Flyway owns a NON-public `audit_event` schema.
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.baseline-on-migrate", () -> "true");
        registry.add("spring.flyway.table", () -> "audit_consumer_flyway_history");
        registry.add("spring.flyway.default-schema", () -> AUDIT_SCHEMA);
        registry.add("spring.flyway.schemas", () -> AUDIT_SCHEMA);

        // Mirror production: no DDL from Hibernate; default_schema only ever
        // rewrites Hibernate-GENERATED SQL — never the native INSERT.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> AUDIT_SCHEMA);

        registry.add("spring.data.redis.host", redisContainer::getHost);
        registry.add("spring.data.redis.port", () -> redisContainer.getMappedPort(6379));

        // Tight poll/reclaim loop so the test observes the persist outcome quickly,
        // and a low error backoff so the failing-insert retry cadence is brisk.
        registry.add("audit.consumer.enabled", () -> "true");
        registry.add("audit.consumer.poll.block-millis", () -> "250");
        registry.add("audit.consumer.poll.claim-min-idle-millis", () -> "500");
        registry.add("audit.consumer.poll.error-backoff-millis", () -> "200");
        registry.add("audit.consumer.dlq-stream-key", () -> "audit:events:dlq");
        registry.add("eureka.client.enabled", () -> "false");
    }

    /**
     * Build the EXACT field map the live {@code RedisStreamAuditSink} producer
     * emits: numeric-string {@code tenantId}/{@code userId}, same key set.
     */
    protected Map<String, String> rejectedEvent(long tenantId, String sessionId, long chunkSeq,
                                                int httpStatus, String rejectionCode,
                                                Long retryAfterSeconds, long timestampMs) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("eventType", EVENT_TYPE);
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

    protected void xadd(Map<String, String> fields) {
        redis.opsForStream().add(StreamRecords.mapBacked(fields).withStreamKey(STREAM_KEY));
    }

    /** Per-tenant persisted row count via the Hibernate-GENERATED (schema-qualified) finder. */
    protected long rowCount(long tenantId) {
        return repository.findByTenantIdOrderBySeqAsc(tenantId).size();
    }

    /**
     * Pending (delivered-but-unacked) entries in the consumer group's PEL.
     * Defensive: returns 0 if the group does not exist yet (NOGROUP) so an
     * Awaitility {@code until(...)} simply keeps polling instead of erroring.
     */
    protected long pendingCount() {
        try {
            var summary = redis.opsForStream().pending(STREAM_KEY, props.getGroup().getName());
            return summary == null ? 0L : summary.getTotalPendingMessages();
        } catch (RuntimeException ex) {
            return 0L;
        }
    }

    /**
     * Walk the cause chain to the underlying {@link SQLException} and return its
     * {@code SQLState}. For the search_path bug this is PostgreSQL {@code 42P01}
     * ({@code undefined_table}) — a LOCALE-INDEPENDENT, version-stable proof of
     * "relation does not exist" (stronger than matching the English error text).
     */
    protected static String sqlStateOf(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getSQLState() != null) {
                return sql.getSQLState();
            }
        }
        return null;
    }

    /**
     * {@code to_regclass(relation)::text} on the runtime datasource connection —
     * resolves {@code relation} against THIS connection's {@code search_path}.
     * Returns {@code null} when the relation is not visible/absent (PostgreSQL
     * {@code to_regclass} yields NULL rather than raising). The {@code CAST(? AS
     * text)} removes any bind-parameter type ambiguity.
     */
    protected String regclass(String relation) {
        return jdbc.queryForObject("SELECT to_regclass(CAST(? AS text))::text", String.class, relation);
    }
}

package com.example.auditconsumer.schema;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.awaitility.Awaitility.await;

/**
 * Faz 24 KVKK audit pipeline (gitops#1249) — REPRODUCTION of the production-only
 * native-INSERT search_path bug (the failure gitops PR #1648 fixed; Codex thread
 * 019ed4bb).
 *
 * <p>This context reproduces the EXACT production schema layout — Flyway builds
 * the {@code audit_event} table in a NON-public {@code audit_event} schema — and
 * configures the runtime datasource <b>WITHOUT</b> {@code currentSchema}. The
 * connection {@code search_path} is therefore the default ({@code "$user",public}),
 * which resolves to {@code public} (no {@code test} schema exists). The
 * Hibernate-GENERATED finders are schema-qualified via
 * {@code hibernate.default_schema=audit_event} and keep working, but the NATIVE
 * unqualified {@code INSERT INTO audit_event} cannot resolve and fails with
 * {@code relation "audit_event" does not exist}. The consumer treats that as a
 * {@code DataAccessException} and does NOT ACK — so the event is left stuck in the
 * Redis Streams PEL (the live symptom).
 *
 * <p>The original {@code AuditPipelineEndToEndPostgresIntegrationTest} missed this
 * whole class of bug because it pinned Flyway + Hibernate to {@code public}, so
 * the unqualified native insert always resolved.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditEventSchemaSearchPathBugReproductionPostgresIntegrationTest
        extends AbstractAuditEventSchemaSearchPathSupport {

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
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registerCommonProperties(registry, POSTGRES, REDIS);
        // THE BUG: runtime datasource WITHOUT ?currentSchema=audit_event. The
        // connection search_path stays `public`, so the unqualified native
        // INSERT INTO audit_event cannot resolve to audit_event.audit_event.
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    }

    private static final long BUG_TENANT = 5101L;

    @Test
    void productionSchemaLayoutPlacesTheTableOffThePublicSearchPath() {
        // (1) Flyway built the PRODUCTION layout: the table exists in the
        //     NON-public `audit_event` schema, and NOT in `public`.
        assertThat(regclass("audit_event.audit_event"))
                .as("table must exist in the non-public audit_event schema")
                .isNotNull();
        assertThat(regclass("public.audit_event"))
                .as("table must NOT be in public (the layout the old test wrongly used)")
                .isNull();

        // (2) THE SEARCH_PATH BUG, distilled: on this no-currentSchema connection
        //     the UNQUALIFIED name resolves against `public` and is absent — the
        //     exact resolution the native INSERT INTO audit_event performs.
        assertThat(regclass("audit_event"))
                .as("unqualified audit_event must be UNRESOLVABLE under the public search_path")
                .isNull();
    }

    @Test
    void nativeUnqualifiedInsertFailsWithRelationNotFound() {
        long ts = 1_700_002_000_000L;
        TransactionTemplate tx = new TransactionTemplate(txManager);

        // The REAL repository native INSERT (the production persist statement),
        // fully-formed so the ONLY reason it can fail is relation resolution:
        // unqualified `audit_event` → relation "audit_event" does not exist.
        Throwable thrown = catchThrowable(() -> tx.executeWithoutResult(status -> repository.insertOnConflictDoNothing(
                UUID.randomUUID(), BUG_TENANT, EVENT_TYPE, "bug-sess",
                7L, 1L, 413, "OVERSIZE", null, "corr-bug",
                Instant.ofEpochMilli(ts).truncatedTo(ChronoUnit.MICROS),
                EVENT_TYPE + ":bug-sess:1", "1-0",
                null, "a".repeat(64), "SHA-256", 1)));
        assertThat(thrown)
                .as("the unqualified native INSERT must fail under the public search_path")
                .isInstanceOf(DataAccessException.class);
        assertThat(sqlStateOf(thrown))
                .as("PostgreSQL 42P01 undefined_table — relation \"audit_event\" does not exist")
                .isEqualTo("42P01");

        // Isolation: the Hibernate-GENERATED finder IS schema-qualified
        // (hibernate.default_schema=audit_event), so it resolves fine — proving
        // the table genuinely exists and ONLY the native (un-rewritten) SQL breaks.
        assertThat(repository.findByTenantIdOrderBySeqAsc(BUG_TENANT)).isEmpty();
    }

    @Test
    void realServicePersistPathFailsAndIsNotSwallowed() {
        long ts = 1_700_002_100_000L;
        Map<String, String> event = rejectedEvent(BUG_TENANT, "svc-sess", 1, 413, "OVERSIZE", null, ts);

        // The REAL persistence-service path: existsByDedupKey + the advisory lock +
        // the chain-tail read are all schema-qualified (or schema-independent) and
        // succeed, then it trips on the native INSERT — the failure surfaces as a
        // DataAccessException out of persist() (NOT silently swallowed).
        Throwable thrown = catchThrowable(() -> persistence.persist(event, "1-1"));
        assertThat(thrown)
                .as("persist() must propagate the native-INSERT failure, not swallow it")
                .isInstanceOf(DataAccessException.class);
        assertThat(sqlStateOf(thrown))
                .as("PostgreSQL 42P01 undefined_table")
                .isEqualTo("42P01");

        assertThat(rowCount(BUG_TENANT))
                .as("nothing is persisted while the search_path bug is present")
                .isZero();
    }

    @Test
    void consumedEventStaysUnackedInThePelAndIsNeverPersisted() {
        long ts = 1_700_002_200_000L;
        xadd(rejectedEvent(BUG_TENANT, "pel-sess", 1, 413, "OVERSIZE", null, ts));

        // The consumer reads it (→ PEL) and the persist throws a DataAccessException
        // which handleRecord does NOT ACK → the entry is stuck pending. This is the
        // exact production symptom: events left unacked in the Redis Streams PEL.
        await().atMost(Duration.ofSeconds(20)).pollInterval(Duration.ofMillis(200))
                .until(() -> pendingCount() >= 1);

        // ...and it STAYS stuck + unpersisted across a sustained window (the
        // failing insert is retried via reclaim every cycle, never acking).
        await().during(Duration.ofSeconds(3)).atMost(Duration.ofSeconds(8))
                .pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> {
                    assertThat(rowCount(BUG_TENANT)).isZero();
                    assertThat(pendingCount()).isGreaterThanOrEqualTo(1L);
                });
    }
}

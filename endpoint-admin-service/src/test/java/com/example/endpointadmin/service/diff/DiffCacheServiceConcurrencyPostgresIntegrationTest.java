package com.example.endpointadmin.service.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-024c v2-c-pre-2-C-B Category D concurrency smoke (Codex 019e89e8
 * iter-5 deferred mandatory coverage). Two threads race
 * {@link DiffCacheService#upsertSoftwareDiffCache} against the same
 * {@code (tenant, device)} pair with different source-pair tuples to
 * verify PostgreSQL {@code ON CONFLICT DO UPDATE WHERE} atomicity holds
 * + writer guard logic survives contention.
 *
 * <p>Invariants under contention (Codex 019e89a3 iter-3 plan):
 * <ul>
 *   <li>Exactly one thread reports {@code true} (the upsert that
 *       satisfied the source-pair guard and the any-diff predicate).</li>
 *   <li>Final cache row reflects the newer tuple — there is no
 *       interleaved torn row where status / counts come from one writer
 *       and source-tuple from the other.</li>
 *   <li>Stale-attempt thread either reports {@code false} (its upsert
 *       hit the WHERE guard and changed nothing) or {@code true} but is
 *       then OVERWRITTEN by the winning thread (which would still be
 *       observable as final tuple matches the winning writer).</li>
 * </ul>
 *
 * <p>The test runs 10 trials so a single rare interleaving cannot
 * silently pass — any non-atomic interleaving has many chances to
 * surface across the trials.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DiffCacheService.class)
// @Rollback(false): the test seed (insertDevice + insertSoftwareHistory)
// MUST be committed before the spawned threads can see it. Default
// @DataJpaTest rollback rolls back at end-of-test, but the threads run
// against the auto-commit JdbcTemplate path that opens its own
// transaction — so without commit, the threads' FK lookups against
// endpoint_devices fail (swdc_device_fk violation).
@Rollback(false)
class DiffCacheServiceConcurrencyPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @Autowired private DiffCacheService diffCacheService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private void seedCommit(Runnable seed) {
        // Seed must commit BEFORE the concurrent threads start so the
        // foreign-key targets are visible from the other tx/connection.
        // Default test-level @Transactional opens an outer tx; a nested
        // TransactionTemplate would create a SAVEPOINT (not an independent
        // commit). REQUIRES_NEW suspends the outer tx and commits this
        // seed independently so the spawned threads' fresh connections
        // see it via MVCC.
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> seed.run());
    }

    @Test
    void twoThreads_racingUpsert_finalTupleMatchesNewerThread() throws Exception {
        // 10 trials so a rare interleaving does not silently pass.
        for (int trial = 0; trial < 10; trial++) {
            UUID tenant = UUID.randomUUID();
            final UUID[] holder = new UUID[3];
            Instant tOlder = Instant.parse("2026-06-02T10:00:00Z");
            Instant tNewer = Instant.parse("2026-06-02T10:01:00Z");
            seedCommit(() -> {
                holder[0] = insertDevice(tenant);
                holder[1] = insertSoftwareHistory(tenant, holder[0], tOlder, tOlder);
                holder[2] = insertSoftwareHistory(tenant, holder[0], tNewer, tNewer);
            });
            UUID device = holder[0];
            UUID hOlder = holder[1];
            UUID hNewer = holder[2];

            // Both threads INSERT against the same (tenant, device).
            // Thread A: older tuple (hOlder->hOlder, counts {1,1,1}, tuple (tOlder, tOlder, hOlder))
            // Thread B: newer tuple (hOlder->hNewer, counts {9,8,7}, tuple (tNewer, tNewer, hNewer))
            // The newer must win — final cache row must match thread B.
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(2);
            AtomicBoolean aWrote = new AtomicBoolean(false);
            AtomicBoolean bWrote = new AtomicBoolean(false);
            Throwable[] errors = new Throwable[2];

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> {
                    try {
                        start.await();
                        boolean w = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                                SoftwareDiffSummary.ok(hOlder, hOlder, 1, 1, 1, tOlder, tOlder));
                        aWrote.set(w);
                    } catch (Throwable t) {
                        errors[0] = t;
                    } finally {
                        done.countDown();
                    }
                });
                pool.submit(() -> {
                    try {
                        start.await();
                        boolean w = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                                SoftwareDiffSummary.ok(hOlder, hNewer, 9, 8, 7, tNewer, tNewer));
                        bWrote.set(w);
                    } catch (Throwable t) {
                        errors[1] = t;
                    } finally {
                        done.countDown();
                    }
                });
                start.countDown();
                boolean finished = done.await(10, TimeUnit.SECONDS);
                assertThat(finished).as("trial %d: both threads should finish", trial).isTrue();
            } finally {
                pool.shutdownNow();
            }

            // No exception in either thread.
            for (int i = 0; i < 2; i++) {
                if (errors[i] != null) {
                    throw new AssertionError("trial " + trial + " thread " + i + " failed", errors[i]);
                }
            }
            // At least one thread wrote (otherwise no INSERT happened).
            assertThat(aWrote.get() || bWrote.get())
                    .as("trial %d: at least one thread must write", trial).isTrue();

            // Final cache row reflects thread B (newer tuple) — atomicity
            // invariant. Even if A wrote first (INSERT) and B then UPDATEd,
            // the final state is B's.
            Map<String, Object> row = readSoftwareCacheRow(tenant, device);
            assertThat(row).as("trial %d: final cache row must exist", trial).isNotNull();
            assertThat(row.get("to_history_id"))
                    .as("trial %d: final to_history_id must be the newer-tuple thread B's", trial)
                    .isEqualTo(hNewer);
            assertThat(row.get("source_row_id"))
                    .as("trial %d: source_row_id matches B", trial)
                    .isEqualTo(hNewer);
            assertThat(row.get("added_count"))
                    .as("trial %d: counts match B (no torn row)", trial)
                    .isEqualTo(9);
            assertThat(row.get("removed_count"))
                    .as("trial %d: removed count matches B", trial)
                    .isEqualTo(8);
            assertThat(row.get("version_changed_count"))
                    .as("trial %d: version_changed count matches B", trial)
                    .isEqualTo(7);
        }
    }

    @Test
    void twoThreads_identicalTuple_atMostOneReportsTrue() throws Exception {
        // Both threads attempt the SAME tuple. With INSERT semantics,
        // exactly one INSERTs (returns true) and the other hits ON
        // CONFLICT DO UPDATE WHERE — which evaluates to false because all
        // any-diff cols (status, from, to, counts, source_*) are identical
        // — so the second thread returns false (idempotency).
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[3];
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            holder[1] = insertSoftwareHistory(tenant, holder[0], t, t);
            holder[2] = insertSoftwareHistory(tenant, holder[0], t.plusSeconds(60), t.plusSeconds(60));
        });
        UUID device = holder[0];
        UUID h1 = holder[1];
        UUID h2 = holder[2];
        SoftwareDiffSummary identical = SoftwareDiffSummary.ok(h1, h2, 5, 4, 3,
                t.plusSeconds(60), t.plusSeconds(60));

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicBoolean wrote1 = new AtomicBoolean(false);
        AtomicBoolean wrote2 = new AtomicBoolean(false);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                try { start.await();
                    wrote1.set(diffCacheService.upsertSoftwareDiffCache(tenant, device, identical));
                } catch (InterruptedException ignored) {
                } finally { done.countDown(); }
            });
            pool.submit(() -> {
                try { start.await();
                    wrote2.set(diffCacheService.upsertSoftwareDiffCache(tenant, device, identical));
                } catch (InterruptedException ignored) {
                } finally { done.countDown(); }
            });
            start.countDown();
            assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // Identical-payload concurrent contention: PG's ON CONFLICT DO
        // UPDATE WHERE branch — at most one INSERT, the other goes via
        // UPDATE-with-WHERE-false → no update → false. So exactly one
        // true, exactly one false. (Both true would mean WHERE wrongly
        // matched; both false would mean neither INSERTed.)
        int trueCount = (wrote1.get() ? 1 : 0) + (wrote2.get() ? 1 : 0);
        assertThat(trueCount).as("identical payload: exactly one writer reports true")
                .isEqualTo(1);
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row).isNotNull();
        assertThat(row.get("added_count")).isEqualTo(5);
    }

    // ─────────────────────────── seed helpers ─────────────────────────

    private UUID insertDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        String hostname = "host-" + id.toString().substring(0, 8);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, hostname, now, now);
        return id;
    }

    private UUID insertSoftwareHistory(UUID tenant, UUID device,
                                        Instant capturedAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp captured = Timestamp.from(capturedAt);
        Timestamp created = Timestamp.from(createdAt);
        String seed = id.toString().toLowerCase().replaceAll("[^0-9a-f]", "");
        String hashFull = (seed + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_inventory_state_history "
                        + "(id, tenant_id, device_id, schema_version, "
                        + " app_count, apps_digest_hash, apps_digest, "
                        + " captured_at, created_at) "
                        + "VALUES (?, ?, ?, 1, 0, ?, '[]'::jsonb, ?, ?)",
                id, tenant, device, hashFull, captured, created);
        return id;
    }

    private Map<String, Object> readSoftwareCacheRow(UUID tenant, UUID device) {
        return jdbc.queryForList(
                "SELECT status, from_history_id, to_history_id, added_count, "
                + "removed_count, version_changed_count, source_captured_at, "
                + "source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_software_diff_cache "
                + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device).stream().findFirst().orElse(null);
    }
}

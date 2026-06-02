package com.example.endpointadmin.service.diff;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.service.EndpointOutdatedSoftwareDiffService;
import com.example.endpointadmin.service.EndpointSoftwareInventoryDiffService;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * BE-024c v2-c-pre-2-C-B {@link DiffCacheBackfillService} integration test
 * against a real PostgreSQL container (Codex 019e89e8 iter-5 AGREE on
 * split required-coverage pre-merge).
 *
 * <h2>Test categories</h2>
 * <ul>
 *   <li><b>backfill creates fresh cache row</b> — device with no prior cache
 *       (only ingest path lagged) gets a cache row via the sweep.</li>
 *   <li><b>backfill is idempotent</b> — second sweep against a current cache
 *       row counts as unchanged (writer source-pair guard reject = zero
 *       churn; Codex 019e8a09 iter-1 must-fix #4 honest naming).</li>
 *   <li><b>backfillBatch explicit deviceIds</b> — scope narrows to just the
 *       provided devices, leaves the rest untouched.</li>
 *   <li><b>backfill per-device exception isolation</b> — when one device's
 *       refresh path fails (deleted device but lingering history), the
 *       per-device catch keeps the batch going + errors counter goes up by
 *       one + the other device still backfills.</li>
 *   <li><b>backfillTenant pageSize boundary</b> — pageSize=1 walks the
 *       device list one row at a time and totals correctly.</li>
 * </ul>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({DiffCacheBackfillService.class, DiffCacheBackfillDeviceRefresher.class,
        DiffCacheService.class,
        EndpointSoftwareInventoryDiffService.class,
        EndpointOutdatedSoftwareDiffService.class})
// @Rollback(false): the post-Codex-iter-1-absorb baseline now routes per-
// device refresh through DiffCacheBackfillDeviceRefresher's @Transactional
// REQUIRES_NEW boundary. REQUIRES_NEW suspends the outer test tx and opens
// a fresh one, which under @DataJpaTest's default test-rollback cannot see
// the uncommitted seed (FK lookups for endpoint_devices fail with
// swdc_device_fk). Forcing commit at end-of-test + the seedCommit()
// pattern fix this — seed runs in a REQUIRES_NEW so its rows are visible
// to the refresher's REQUIRES_NEW too.
@Rollback(false)
class DiffCacheBackfillServicePostgresIntegrationTest {

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

    @Autowired private DiffCacheBackfillService backfillService;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager txManager;

    private void seedCommit(Runnable seed) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.executeWithoutResult(status -> seed.run());
    }

    @Test
    void backfillTenant_freshCacheRowCreated_software() {
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[1];
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            insertSoftwareHistory(tenant, holder[0], t1, t1);
            insertSoftwareHistory(tenant, holder[0], t2, t2);
        });
        UUID device = holder[0];

        // No prior cache row. backfillTenant should fill it.
        DiffCacheBackfillResult result =
                backfillService.backfillTenant(tenant, DiffType.SOFTWARE, 200);

        assertThat(result.checked()).isEqualTo(1L);
        assertThat(result.changed()).isEqualTo(1L);
        assertThat(result.unchanged()).isEqualTo(0L);
        assertThat(result.errors()).isEqualTo(0L);
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row).as("cache row created").isNotNull();
        assertThat(row.get("status")).isIn("OK", "NO_CHANGE");
    }

    @Test
    void backfillTenant_idempotentSecondSweep_unchanged() {
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[1];
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            insertSoftwareHistory(tenant, holder[0], t1, t1);
            insertSoftwareHistory(tenant, holder[0], t2, t2);
        });
        UUID device = holder[0];

        DiffCacheBackfillResult first =
                backfillService.backfillTenant(tenant, DiffType.SOFTWARE, 200);
        DiffCacheBackfillResult second =
                backfillService.backfillTenant(tenant, DiffType.SOFTWARE, 200);

        assertThat(first.changed()).isEqualTo(1L);
        // Second sweep against the same tuple — writer guard rejects.
        assertThat(second.checked()).isEqualTo(1L);
        assertThat(second.changed()).isEqualTo(0L);
        assertThat(second.unchanged()).isEqualTo(1L);
        assertThat(second.errors()).isEqualTo(0L);
    }

    @Test
    void backfillBatch_explicitDeviceIds_scopesCorrectly() {
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[3];
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            holder[1] = insertDevice(tenant);
            holder[2] = insertDevice(tenant);
            for (UUID d : List.of(holder[0], holder[1], holder[2])) {
                insertSoftwareHistory(tenant, d, t, t);
                insertSoftwareHistory(tenant, d, t.plusSeconds(60), t.plusSeconds(60));
            }
        });
        UUID deviceA = holder[0];
        UUID deviceB = holder[1];
        UUID deviceC = holder[2];

        // Scope = only A + B; C should stay untouched.
        DiffCacheBackfillResult result = backfillService.backfillBatch(
                tenant, DiffType.SOFTWARE, List.of(deviceA, deviceB));

        assertThat(result.checked()).isEqualTo(2L);
        assertThat(result.changed()).isEqualTo(2L);
        assertThat(result.errors()).isEqualTo(0L);
        assertThat(readSoftwareCacheRow(tenant, deviceA)).isNotNull();
        assertThat(readSoftwareCacheRow(tenant, deviceB)).isNotNull();
        assertThat(readSoftwareCacheRow(tenant, deviceC)).as("device C out of scope").isNull();
    }

    @Test
    void backfillTenant_pageSizeOne_walksDevicesOneAtATime() {
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[3];
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            holder[1] = insertDevice(tenant);
            holder[2] = insertDevice(tenant);
            for (UUID d : List.of(holder[0], holder[1], holder[2])) {
                insertSoftwareHistory(tenant, d, t, t);
                insertSoftwareHistory(tenant, d, t.plusSeconds(60), t.plusSeconds(60));
            }
        });

        DiffCacheBackfillResult result =
                backfillService.backfillTenant(tenant, DiffType.SOFTWARE, 1);

        assertThat(result.checked()).isEqualTo(3L);
        assertThat(result.changed()).isEqualTo(3L);
        assertThat(result.errors()).isEqualTo(0L);
    }

    @Test
    void backfillTenant_outdated_freshCacheRowCreated() {
        // Mirror — outdated path uses snapshot table.
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[1];
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            insertOutdatedSnapshot(tenant, holder[0], t, t);
            insertOutdatedSnapshot(tenant, holder[0], t.plusSeconds(60), t.plusSeconds(60));
        });
        UUID device = holder[0];

        DiffCacheBackfillResult result =
                backfillService.backfillTenant(tenant, DiffType.OUTDATED, 200);

        assertThat(result.checked()).isEqualTo(1L);
        assertThat(result.changed()).isEqualTo(1L);
        assertThat(result.errors()).isEqualTo(0L);
        Map<String, Object> row = readOutdatedCacheRow(tenant, device);
        assertThat(row).as("outdated cache row created").isNotNull();
    }

    @Test
    void backfillTenant_tenantBoundary_otherTenantUntouched() {
        // Tenant A backfill must not touch tenant B's cache.
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        final UUID[] holder = new UUID[2];
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenantA);
            holder[1] = insertDevice(tenantB);
            insertSoftwareHistory(tenantA, holder[0], t, t);
            insertSoftwareHistory(tenantA, holder[0], t.plusSeconds(60), t.plusSeconds(60));
            insertSoftwareHistory(tenantB, holder[1], t, t);
            insertSoftwareHistory(tenantB, holder[1], t.plusSeconds(60), t.plusSeconds(60));
        });
        UUID deviceA = holder[0];
        UUID deviceB = holder[1];

        DiffCacheBackfillResult result =
                backfillService.backfillTenant(tenantA, DiffType.SOFTWARE, 200);

        assertThat(result.checked()).isEqualTo(1L);
        assertThat(readSoftwareCacheRow(tenantA, deviceA)).isNotNull();
        assertThat(readSoftwareCacheRow(tenantB, deviceB))
                .as("tenant B untouched").isNull();
    }

    @Test
    void backfillBatch_oneDeviceThrows_continuesAndCountsError() {
        // Codex 019e8a09 iter-3 follow-up: Javadoc claims "per-device
        // exception isolation" coverage but tests didn't directly
        // exercise that path. Drop in a device id that does NOT exist
        // in endpoint_devices (real DB-level FK ↔ summary() exception)
        // alongside a valid device — the per-device catch must isolate
        // the failure and let the valid device backfill, errors count
        // goes up by exactly one.
        UUID tenant = UUID.randomUUID();
        final UUID[] holder = new UUID[1];
        Instant t = Instant.parse("2026-06-02T10:00:00Z");
        seedCommit(() -> {
            holder[0] = insertDevice(tenant);
            insertSoftwareHistory(tenant, holder[0], t, t);
            insertSoftwareHistory(tenant, holder[0], t.plusSeconds(60), t.plusSeconds(60));
        });
        UUID validDevice = holder[0];
        // Synthetic device id not present in endpoint_devices — the
        // refresher's summary path WILL still run (it reads from
        // state_history); the upsert that follows hits the swdc_device_fk
        // foreign-key violation because the FK targets endpoint_devices
        // (tenant_id, id), and no row exists for this synthetic device.
        UUID ghostDevice = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff0");

        DiffCacheBackfillResult result = backfillService.backfillBatch(
                tenant, DiffType.SOFTWARE, List.of(validDevice, ghostDevice));

        assertThat(result.checked()).as("both devices counted").isEqualTo(2L);
        // The valid device backfilled OR was idempotent (depending on
        // whether the per-device REQUIRES_NEW commits before the ghost
        // device's exception). Allow both — what we care about is that
        // the valid device's refresh didn't abort and the error count
        // is exactly one.
        assertThat(result.changed() + result.unchanged())
                .as("valid device produced a deterministic write outcome").isEqualTo(1L);
        assertThat(result.errors()).as("ghost device counted as exactly one error")
                .isEqualTo(1L);
    }

    @Test
    void backfillTenant_invalidPageSize_throws() {
        UUID tenant = UUID.randomUUID();
        // pageSize=0 invalid.
        try {
            backfillService.backfillTenant(tenant, DiffType.SOFTWARE, 0);
            assertThat(false).as("expected IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("pageSize");
        }
        // pageSize=5001 invalid.
        try {
            backfillService.backfillTenant(tenant, DiffType.SOFTWARE, 5001);
            assertThat(false).as("expected IllegalArgumentException").isTrue();
        } catch (IllegalArgumentException expected) {
            assertThat(expected.getMessage()).contains("pageSize");
        }
    }

    // ─────────────────────────── seed helpers ─────────────────────────

    private UUID insertDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        // Hostname must be unique per (tenant, hostname) per the UNIQUE
        // constraint uq_endpoint_devices_tenant_hostname — use id-derived
        // suffix so multi-device batch tests stay within the boundary.
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

    private UUID insertOutdatedSnapshot(UUID tenant, UUID device,
                                         Instant collectedAt, Instant createdAt) {
        UUID id = UUID.randomUUID();
        Timestamp collected = Timestamp.from(collectedAt);
        Timestamp created = Timestamp.from(createdAt);
        String hash = id.toString().replace("-", "");
        hash = (hash + hash).substring(0, 64);
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_outdated_software_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " probe_complete, upgrade_count, upgrade_truncated, max_upgrade, "
                        + " source_used, payload_hash_sha256, collected_at, created_at) "
                        + "VALUES (?, ?, ?, 1, true, true, 0, false, 100, 'winget', ?, ?, ?)",
                id, tenant, device, hash, collected, created);
        return id;
    }

    private Map<String, Object> readSoftwareCacheRow(UUID tenant, UUID device) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, from_history_id, to_history_id, added_count, "
                + "removed_count, version_changed_count, source_captured_at, "
                + "source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_software_diff_cache "
                + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private Map<String, Object> readOutdatedCacheRow(UUID tenant, UUID device) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT status, from_snapshot_id, to_snapshot_id, added_count, "
                + "removed_count, version_changed_count, available_version_bumped_count, "
                + "source_captured_at, source_created_at, source_row_id "
                + "FROM " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
        return rows.isEmpty() ? null : rows.get(0);
    }
}

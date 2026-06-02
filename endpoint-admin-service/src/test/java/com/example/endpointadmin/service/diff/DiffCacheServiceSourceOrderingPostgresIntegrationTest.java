package com.example.endpointadmin.service.diff;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * BE-024c v2-c-pre-2-C-A (Faz 22.5 P2-A, Codex 019e89a3 iter-4 mandatory
 * acceptance) — direct stale source-pair guard + progression + same
 * timestamp tiebreaker tests for {@link DiffCacheService} writer against
 * a real PostgreSQL container.
 *
 * <p>These tests use the tuple-aware factory overloads so the writer
 * source-pair ordering guard receives the real history row's
 * {@code (captured_at, created_at, id)} tuple — NOT the epoch sentinel
 * the legacy factories would produce.
 *
 * <h2>Test categories (Codex iter-4 mandatory)</h2>
 * <ul>
 *   <li><b>A:</b> stale source-pair direct overwrite reject (software +
 *       outdated).</li>
 *   <li><b>B:</b> progression matrix — NO_HISTORY → INSUFFICIENT → OK
 *       allow; OK → NO_HISTORY downgrade block; OK → INSUFFICIENT from-
 *       downgrade block.</li>
 *   <li><b>B+:</b> same {@code captured_at}/{@code collected_at}, newer
 *       {@code created_at}/{@code id} stale reject (tiebreaker).</li>
 * </ul>
 *
 * <p>V28 backfill (B++), listener consistency (C), and concurrency smoke
 * (D) ship in separate test files / follow-up PR (disclosed honestly
 * pre-merge).
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(DiffCacheService.class)
class DiffCacheServiceSourceOrderingPostgresIntegrationTest {

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

    // ─────────────────────────── Category A — direct stale ─────────────────────────

    @Test
    void software_stalePairOverwrite_isBlocked() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        // h1 → h2 → h3 with strictly increasing captured_at.
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        Instant t3 = Instant.parse("2026-06-02T10:02:00Z");
        UUID h1 = insertSoftwareHistory(tenant, device, t1, t1);
        UUID h2 = insertSoftwareHistory(tenant, device, t2, t2);
        UUID h3 = insertSoftwareHistory(tenant, device, t3, t3);

        // Newer summary lands first: cache row = h2→h3 OK (5, 4, 3) with
        // tuple (t3, t3, h3).
        diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h2, h3, 5, 4, 3, t3, t3));

        // Stale summary attempt: h1→h2 with tuple (t2, t2, h2). Should be
        // blocked by source tuple guard (t2 < t3).
        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 0, 0, 0, t2, t2));

        assertThat(wrote).as("stale source-pair must be blocked").isFalse();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("to_history_id")).as("newer to_history_id preserved").isEqualTo(h3);
        assertThat(row.get("added_count")).isEqualTo(5);
        assertThat(((Timestamp) row.get("source_captured_at")).toInstant()).isEqualTo(t3);
    }

    @Test
    void outdated_stalePairOverwrite_isBlocked() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        Instant t3 = Instant.parse("2026-06-02T10:02:00Z");
        UUID s1 = insertOutdatedSnapshot(tenant, device, t1, t1);
        UUID s2 = insertOutdatedSnapshot(tenant, device, t2, t2);
        UUID s3 = insertOutdatedSnapshot(tenant, device, t3, t3);

        diffCacheService.upsertOutdatedDiffCache(tenant, device,
                OutdatedDiffSummary.ok(s2, s3, 7, 6, 5, 4, t3, t3));

        boolean wrote = diffCacheService.upsertOutdatedDiffCache(tenant, device,
                OutdatedDiffSummary.ok(s1, s2, 0, 0, 0, 0, t2, t2));

        assertThat(wrote).as("stale outdated source-pair must be blocked").isFalse();
        Map<String, Object> row = readOutdatedCacheRow(tenant, device);
        assertThat(row.get("to_snapshot_id")).isEqualTo(s3);
        assertThat(row.get("available_version_bumped_count")).isEqualTo(4);
    }

    // ─────────────────────────── Category B — progression matrix ─────────────────────────

    @Test
    void software_noHistoryToInsufficient_isAllowedProgression() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        UUID h1 = insertSoftwareHistory(tenant, device, t1, t1);

        diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.noHistory());

        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.insufficientHistory(h1, t1, t1));

        assertThat(wrote).as("NO_HISTORY → INSUFFICIENT allow").isTrue();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("status")).isEqualTo("INSUFFICIENT_HISTORY");
        assertThat(row.get("to_history_id")).isEqualTo(h1);
    }

    @Test
    void software_okToNoHistory_isBlockedDowngrade() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        UUID h1 = insertSoftwareHistory(tenant, device, t1, t1);
        UUID h2 = insertSoftwareHistory(tenant, device, t2, t2);

        diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 1, 1, 1, t2, t2));
        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.noHistory());

        assertThat(wrote).as("OK → NO_HISTORY downgrade block").isFalse();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("status")).isEqualTo("OK");
        assertThat(row.get("to_history_id")).isEqualTo(h2);
    }

    @Test
    void software_okToInsufficient_isBlockedDowngrade() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        Instant t1 = Instant.parse("2026-06-02T10:00:00Z");
        Instant t2 = Instant.parse("2026-06-02T10:01:00Z");
        UUID h1 = insertSoftwareHistory(tenant, device, t1, t1);
        UUID h2 = insertSoftwareHistory(tenant, device, t2, t2);

        diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, h2, 2, 2, 2, t2, t2));
        // Same to_history_id (h2) but from null → from-downgrade block.
        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.insufficientHistory(h2, t2, t2));

        assertThat(wrote).as("OK → INSUFFICIENT (from non-null → null) block")
                .isFalse();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("status")).isEqualTo("OK");
        assertThat(row.get("from_history_id")).isEqualTo(h1);
    }

    // ─────────────────────────── Category B+ — same captured_at tiebreaker ─────────────────────────

    @Test
    void software_sameCapturedAtOlderCreatedAt_isBlockedAsStale() {
        // Codex 019e89a3 iter-1 mandatory test: same captured_at, different
        // created_at - the older created_at tuple must NOT overwrite a row
        // already written with the newer created_at tuple. Without the
        // tuple tiebreaker the writer would treat them as equal and the
        // any-diff predicate could let the older overwrite the newer.
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        Instant captured = Instant.parse("2026-06-02T10:00:00Z");
        Instant created1 = Instant.parse("2026-06-02T10:00:01Z"); // older
        Instant created2 = Instant.parse("2026-06-02T10:00:02Z"); // newer
        UUID h1 = insertSoftwareHistory(tenant, device, captured, captured); // h1: tuple (captured, captured, h1)
        UUID hNewerCreated = insertSoftwareHistory(tenant, device, captured, created2);
        UUID hOlderCreated = insertSoftwareHistory(tenant, device, captured, created1);

        // Cache the newer-created-at pair: (h1, hNewerCreated) with tuple
        // (captured, created2, hNewerCreated).
        diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, hNewerCreated, 10, 0, 0,
                        captured, created2));

        // Stale attempt: (h1, hOlderCreated) with tuple (captured, created1,
        // hOlderCreated) - same captured_at, older created_at - should be
        // blocked.
        boolean wrote = diffCacheService.upsertSoftwareDiffCache(tenant, device,
                SoftwareDiffSummary.ok(h1, hOlderCreated, 0, 0, 0,
                        captured, created1));

        assertThat(wrote).as("same captured_at older created_at must be blocked")
                .isFalse();
        Map<String, Object> row = readSoftwareCacheRow(tenant, device);
        assertThat(row.get("to_history_id")).isEqualTo(hNewerCreated);
        assertThat(row.get("added_count")).isEqualTo(10);
    }

    @Test
    void outdated_sameCollectedAtOlderCreatedAt_isBlockedAsStale() {
        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(tenant);
        Instant collected = Instant.parse("2026-06-02T10:00:00Z");
        Instant created1 = Instant.parse("2026-06-02T10:00:01Z");
        Instant created2 = Instant.parse("2026-06-02T10:00:02Z");
        UUID s1 = insertOutdatedSnapshot(tenant, device, collected, collected);
        UUID sNewerCreated = insertOutdatedSnapshot(tenant, device, collected, created2);
        UUID sOlderCreated = insertOutdatedSnapshot(tenant, device, collected, created1);

        diffCacheService.upsertOutdatedDiffCache(tenant, device,
                OutdatedDiffSummary.ok(s1, sNewerCreated, 7, 6, 5, 4,
                        collected, created2));

        boolean wrote = diffCacheService.upsertOutdatedDiffCache(tenant, device,
                OutdatedDiffSummary.ok(s1, sOlderCreated, 0, 0, 0, 0,
                        collected, created1));

        assertThat(wrote).as("outdated same collected_at older created_at block")
                .isFalse();
        Map<String, Object> row = readOutdatedCacheRow(tenant, device);
        assertThat(row.get("to_snapshot_id")).isEqualTo(sNewerCreated);
    }

    // ─────────────────────────── seed helpers ─────────────────────────

    private UUID insertDevice(UUID tenant) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-06-02T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, 'host', 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, tenant, now, now);
        return id;
    }

    /**
     * Insert a software state history row with explicit
     * {@code (captured_at, created_at)} so tests can drive the source-pair
     * tuple guard with realistic data instead of epoch sentinel.
     */
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
        return jdbc.queryForMap(
                "SELECT status, from_history_id, to_history_id, "
                        + "added_count, removed_count, version_changed_count, "
                        + "source_captured_at, source_created_at, source_row_id "
                        + "FROM " + SCHEMA + ".endpoint_software_diff_cache "
                        + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
    }

    private Map<String, Object> readOutdatedCacheRow(UUID tenant, UUID device) {
        return jdbc.queryForMap(
                "SELECT status, from_snapshot_id, to_snapshot_id, "
                        + "added_count, removed_count, version_changed_count, "
                        + "available_version_bumped_count, "
                        + "source_captured_at, source_created_at, source_row_id "
                        + "FROM " + SCHEMA + ".endpoint_outdated_software_diff_cache "
                        + "WHERE tenant_id = ? AND device_id = ?",
                tenant, device);
    }
}

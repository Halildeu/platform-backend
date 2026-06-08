package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * V57 cross-device domain filter cache backfill migration harness PG IT
 * (Faz 22.5, board #517; Codex 019ea83c AGREE Option A).
 *
 * <p>The runtime self-heal ({@code reconcileDeviceDomainProjection}) is
 * covered on H2 by {@code EndpointHardwareInventoryServiceTest}; this class
 * exercises the PG-specific parts the JVM cannot: the {@code DISTINCT ON …
 * ORDER BY collected_at DESC, created_at DESC, id DESC} latest-snapshot
 * tie-breaker and the null-safe {@code IS DISTINCT FROM} idempotent /
 * stale-clearing UPDATE.
 *
 * <p>Mirrors {@code V28BackfillMigrationPostgresIntegrationTest}: a fresh
 * testcontainer migrated to the V56 baseline, seed, run V57 via Flyway
 * {@code target=57}, assert.
 */
@Testcontainers
class V57DeviceDomainBackfillPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin")
                    .withUsername("test")
                    .withPassword("test");

    private DriverManagerDataSource freshDataSource() {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setUrl(POSTGRES.getJdbcUrl());
        ds.setUsername(POSTGRES.getUsername());
        ds.setPassword(POSTGRES.getPassword());
        return ds;
    }

    private void resetSchemaToV56(DriverManagerDataSource ds) {
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
        jdbc.execute("CREATE SCHEMA " + SCHEMA);
        Flyway.configure()
                .dataSource(ds)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target("56")
                .load()
                .migrate();
    }

    private void runV57(DriverManagerDataSource ds) {
        Flyway.configure()
                .dataSource(ds)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .target("57")
                .load()
                .migrate();
    }

    @Test
    void backfillProjectsLatestDomainJoinedSnapshotTrimmedAndLowercased() {
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV56(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant, null);

        // Three snapshots; the LATEST (by collected_at) must win the
        // DISTINCT ON tie-breaker — older domains must be ignored.
        insertSnapshot(jdbc, tenant, device, true, "OLD.local",
                Instant.parse("2026-06-08T10:00:00Z"));
        insertSnapshot(jdbc, tenant, device, true, "mid.local",
                Instant.parse("2026-06-08T11:00:00Z"));
        insertSnapshot(jdbc, tenant, device, true, "  NEW.Local  ",
                Instant.parse("2026-06-08T12:00:00Z"));

        runV57(ds);

        assertThat(domainNameOf(jdbc, device)).isEqualTo("new.local");
    }

    @Test
    void backfillClearsStaleDomainWhenLatestSnapshotIsWorkgroup() {
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV56(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        // Device starts with a stale cached domain (e.g. an old enrollment
        // value) while the latest snapshot says the host left the domain.
        UUID device = insertDevice(jdbc, tenant, "stale.local");

        insertSnapshot(jdbc, tenant, device, true, "stale.local",
                Instant.parse("2026-06-08T10:00:00Z"));
        insertSnapshot(jdbc, tenant, device, false, "WORKGROUP",
                Instant.parse("2026-06-08T12:00:00Z"));

        runV57(ds);

        assertThat(domainNameOf(jdbc, device))
                .as("latest workgroup snapshot must clear the stale cache (fail-clear)")
                .isNull();
    }

    @Test
    void backfillLeavesWorkgroupOnlyDeviceNull() {
        DriverManagerDataSource ds = freshDataSource();
        resetSchemaToV56(ds);
        JdbcTemplate jdbc = new JdbcTemplate(ds);

        UUID tenant = UUID.randomUUID();
        UUID device = insertDevice(jdbc, tenant, null);
        insertSnapshot(jdbc, tenant, device, false, "WORKGROUP",
                Instant.parse("2026-06-08T12:00:00Z"));

        runV57(ds);

        assertThat(domainNameOf(jdbc, device)).isNull();
    }

    // ------------------------------------------------------------------
    // seed helpers — org_id is filled by the V29/V40 triggers on insert,
    // so it is intentionally omitted from these INSERTs.
    // ------------------------------------------------------------------

    private UUID insertDevice(JdbcTemplate jdbc, UUID tenant, String initialDomain) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, hostname, status, os_type, domain_name, "
                        + " last_seen_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'ONLINE', 'WINDOWS', ?, ?, ?, ?, 0)",
                id, tenant, "host-" + id.toString().substring(0, 8), initialDomain,
                now, now, now);
        return id;
    }

    private void insertSnapshot(JdbcTemplate jdbc, UUID tenant, UUID device,
                                Boolean domainJoined, String domainName, Instant collectedAt) {
        UUID id = UUID.randomUUID();
        String hash = (id.toString().replaceAll("[^0-9a-f]", "")
                + "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef")
                .substring(0, 64);
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_hardware_inventory_snapshots "
                        + "(id, tenant_id, device_id, schema_version, supported, "
                        + " domain_joined, domain_name, payload_hash_sha256, "
                        + " redacted_payload, probe_errors, collected_at, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 1, true, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, 0)",
                id, tenant, device, domainJoined, domainName, hash,
                "{}", "[]", Timestamp.from(collectedAt), now, now);
    }

    private String domainNameOf(JdbcTemplate jdbc, UUID device) {
        return jdbc.queryForObject(
                "SELECT domain_name FROM " + SCHEMA + ".endpoint_devices WHERE id = ?",
                String.class, device);
    }
}

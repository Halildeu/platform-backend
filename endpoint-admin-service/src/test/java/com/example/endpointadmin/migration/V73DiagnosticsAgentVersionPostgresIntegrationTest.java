package com.example.endpointadmin.migration;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AG-038 regression guard for live AgentPC2 diagnostics ingest.
 *
 * <p>The Java diagnostics policy accepts a bounded lowercase {@code v}
 * semver prefix, but the original V23 database backstop did not. V73 keeps the
 * database constraint aligned while preserving fail-closed uppercase and
 * malformed version rejection.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class V73DiagnosticsAgentVersionPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String DEVICES = SCHEMA + ".endpoint_devices";
    private static final String SNAPSHOTS = SCHEMA + ".endpoint_diagnostics_snapshots";

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
        registry.add("spring.datasource.driver-class-name",
                () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.default-schema", () -> SCHEMA);
        registry.add("spring.flyway.schemas", () -> SCHEMA);
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> SCHEMA);
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void v73AgentVersionConstraintAllowsLowercaseVPrefixedSemver() {
        UUID orgId = UUID.randomUUID();
        UUID deviceId = seedDevice(orgId);

        insertDiagnosticsSnapshot(orgId, deviceId, "0.2.14", "a".repeat(64));
        insertDiagnosticsSnapshot(orgId, deviceId, "v0.2.14", "b".repeat(64));
        insertDiagnosticsSnapshot(orgId, deviceId, "unknown", "c".repeat(64));

        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM " + SNAPSHOTS + " WHERE device_id = ?",
                Long.class,
                deviceId)).isEqualTo(3L);
    }

    @Test
    void v73AgentVersionConstraintStillRejectsUppercaseOrMalformedPrefixes() {
        UUID orgId = UUID.randomUUID();
        UUID deviceId = seedDevice(orgId);

        assertThatThrownBy(() -> insertDiagnosticsSnapshot(
                orgId, deviceId, "V0.2.14", "d".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThatThrownBy(() -> insertDiagnosticsSnapshot(
                orgId, deviceId, "vv0.2.14", "e".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void v73KeepsSingleCanonicalAgentVersionConstraintName() {
        String definition = jdbc.queryForObject("""
                SELECT pg_get_constraintdef(oid)
                FROM pg_catalog.pg_constraint
                WHERE conrelid = to_regclass(?)
                  AND conname = 'diag_snap_agent_version_re'
                  AND contype = 'c'
                """, String.class, SNAPSHOTS);

        assertThat(definition)
                .contains("agent_version")
                .contains("v?")
                .contains("unknown");
    }

    private UUID seedDevice(UUID orgId) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + DEVICES + " ("
                        + "id, tenant_id, org_id, hostname, machine_fingerprint, "
                        + "status, os_type, os_version, agent_version, "
                        + "created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'ONLINE', 'WINDOWS', "
                        + "'Windows 11', 'v0.2.14', ?, ?, 0)",
                id, orgId, orgId, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    private void insertDiagnosticsSnapshot(
            UUID orgId, UUID deviceId, String agentVersion, String payloadHash) {
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SNAPSHOTS + " ("
                        + "id, tenant_id, device_id, schema_version, "
                        + "supported, probe_complete, agent_version, config_hash, "
                        + "last_poll_latency_ms, backend_dns_reachable, backend_tls_valid, "
                        + "probe_duration_ms, payload_hash_sha256, collected_at) "
                        + "VALUES (?, ?, ?, 1, true, true, ?, 'unknown', "
                        + "25, true, true, 50, ?, ?)",
                UUID.randomUUID(), orgId, deviceId, agentVersion, payloadHash, now);
    }
}

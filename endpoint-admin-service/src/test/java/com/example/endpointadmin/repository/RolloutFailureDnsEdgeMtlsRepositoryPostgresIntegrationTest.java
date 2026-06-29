package com.example.endpointadmin.repository;

import com.example.endpointadmin.model.DeviceStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #527 DNS_EDGE_MTLS diagnostics source selection against real PostgreSQL +
 * Flyway schema. The scanner unit test proves redacted evidence construction;
 * this test proves the selector only returns latest structured DNS/TLS failures.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class RolloutFailureDnsEdgeMtlsRepositoryPostgresIntegrationTest {

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
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> "public");
    }

    private static final Instant NOW = Instant.parse("2026-06-29T10:30:00Z");

    @Autowired
    private EndpointDiagnosticsSnapshotRepository diagnostics;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID tenant;
    private UUID latestDnsFailure;
    private UUID latestTlsFailure;

    @BeforeEach
    void seed() {
        tenant = UUID.randomUUID();

        UUID dnsDevice = insertDevice("fdq-dns-fail", DeviceStatus.ONLINE);
        latestDnsFailure = insertDiagnostics(dnsDevice, "dns-fail", NOW.minusSeconds(60),
                false, true, "DNS_NXDOMAIN");

        UUID tlsDevice = insertDevice("fdq-tls-fail", DeviceStatus.ONLINE);
        latestTlsFailure = insertDiagnostics(tlsDevice, "tls-fail", NOW.minusSeconds(120),
                true, false, "TLS_CERT_EXPIRED");

        UUID recoveredDevice = insertDevice("fdq-recovered", DeviceStatus.ONLINE);
        insertDiagnostics(recoveredDevice, "old-fail", NOW.minusSeconds(300),
                false, true, "DNS_TIMEOUT");
        insertDiagnostics(recoveredDevice, "new-healthy", NOW.minusSeconds(30),
                true, true, null);

        UUID healthyDevice = insertDevice("fdq-healthy", DeviceStatus.ONLINE);
        insertDiagnostics(healthyDevice, "healthy", NOW.minusSeconds(45),
                true, true, null);

        UUID decommissionedDevice = insertDevice("fdq-decom", DeviceStatus.DECOMMISSIONED);
        insertDiagnostics(decommissionedDevice, "decom-fail", NOW.minusSeconds(15),
                false, false, "DNS_TIMEOUT");
    }

    @Test
    void latestDnsTlsFailureQueryExcludesRecoveredHealthyAndDecommissionedDevices() {
        var rows = diagnostics.findLatestDnsTlsFailuresExcludingDeviceStatus(
                DeviceStatus.DECOMMISSIONED, PageRequest.of(0, 10));

        assertThat(rows).extracting("id").containsExactly(latestDnsFailure, latestTlsFailure);
    }

    private UUID insertDevice(String hostname, DeviceStatus status) {
        UUID device = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO endpoint_devices
                  (id, tenant_id, hostname, os_type, status, created_at, updated_at, version)
                VALUES (?, ?, ?, 'WINDOWS', ?, ?, ?, 0)
                """, device, tenant, hostname,
                status.name(), Timestamp.from(NOW), Timestamp.from(NOW));
        return device;
    }

    private UUID insertDiagnostics(UUID device, String label, Instant collectedAt,
                                   boolean dnsReachable, boolean tlsValid, String errorCode) {
        UUID snapshot = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO endpoint_diagnostics_snapshots
                  (id, tenant_id, device_id, source_command_result_id, schema_version, supported,
                   probe_complete, agent_version, config_hash, last_poll_latency_ms,
                   backend_dns_reachable, backend_tls_valid, last_error_occurred_at,
                   last_error_code, last_error_summary, probe_duration_ms, payload_hash_sha256,
                   collected_at, created_at)
                VALUES (?, ?, ?, NULL, 1, true, true, '0.3.1', ?, 125,
                        ?, ?, ?, ?, ?, 250, ?, ?, ?)
                """,
                snapshot,
                tenant,
                device,
                hex64("config-" + label),
                dnsReachable,
                tlsValid,
                errorCode == null ? null : Timestamp.from(collectedAt),
                errorCode,
                errorCode == null ? null : "redacted diagnostics error",
                hex64("payload-" + label),
                Timestamp.from(collectedAt),
                Timestamp.from(collectedAt.plusMillis(1)));
        return snapshot;
    }

    private static String hex64(String label) {
        return String.format("%064x", Math.abs(label.hashCode()));
    }
}

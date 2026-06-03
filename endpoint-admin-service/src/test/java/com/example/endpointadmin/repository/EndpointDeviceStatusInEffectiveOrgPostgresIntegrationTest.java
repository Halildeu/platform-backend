package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.model.DeviceStatus;
import com.example.endpointadmin.model.EndpointDevice;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 PR2b-iv.b4 regression guard —
 * {@link EndpointDeviceRepository#findVisibleToOrgAndStatusIn} canonical
 * effective-org status-set filter (Codex 019e8d1d B-B sub-slice b4 AGREE;
 * P1 parenthesized OR pattern + IN clause).
 *
 * <p>The canonical predicate:
 * <pre>
 *   WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))
 *     AND d.status IN :statuses
 * </pre>
 *
 * <p>Four behavioural assertions (Codex 019e8d1d b4 minimum guards):
 * <ol>
 *   <li>Canonical row matching status filter is returned.</li>
 *   <li>Legacy NULL row matching status filter is returned (V29 trigger
 *       bypassed + pre-assert).</li>
 *   <li>Cross-org rows excluded even when their status matches.</li>
 *   <li>Status filter scope — devices with non-matching status are
 *       excluded; multi-status IN set returns the union.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointDeviceStatusInEffectiveOrgPostgresIntegrationTest {

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
    private EndpointDeviceRepository repository;
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void canonicalRow_matchingStatus_isReturned() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, orgA, "canon-host", DeviceStatus.ONLINE);

        List<EndpointDevice> hits = repository.findVisibleToOrgAndStatusIn(
                orgA, List.of(DeviceStatus.ONLINE));
        assertThat(hits).extracting(EndpointDevice::getId).containsExactly(deviceId);
    }

    @Test
    void legacyNullRow_matchingStatus_isReturnedViaTenantIdFallback_andPreservedAsNullByFixture() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceLegacyNullOrg(deviceId, orgA, "legacy-host", DeviceStatus.ONLINE);

        Boolean orgIdIsNull = jdbc.queryForObject(
                "SELECT org_id IS NULL FROM " + SCHEMA + ".endpoint_devices WHERE id = ?",
                Boolean.class, deviceId);
        assertThat(orgIdIsNull)
                .as("legacy NULL fixture pre-assert: V29 trigger bypass held")
                .isTrue();

        List<EndpointDevice> hits = repository.findVisibleToOrgAndStatusIn(
                orgA, List.of(DeviceStatus.ONLINE));
        assertThat(hits)
                .as("legacy NULL row matching status reachable via OR fallback")
                .extracting(EndpointDevice::getId).containsExactly(deviceId);
    }

    @Test
    void crossOrg_rowMatchingStatus_isExcluded() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        // Both online, but in different orgs.
        insertDeviceCanonical(deviceA, orgA, "orga-online", DeviceStatus.ONLINE);
        insertDeviceCanonical(deviceB, orgB, "orgb-online", DeviceStatus.ONLINE);

        List<EndpointDevice> hits = repository.findVisibleToOrgAndStatusIn(
                orgA, List.of(DeviceStatus.ONLINE));
        assertThat(hits)
                .as("orgA online-filter MUST NOT return orgB's online device")
                .extracting(EndpointDevice::getId).containsExactly(deviceA);
    }

    @Test
    void statusFilter_excludesNonMatching_andMultiStatusInReturnsUnion() {
        UUID orgA = UUID.randomUUID();
        UUID onlineId = UUID.randomUUID();
        UUID offlineId = UUID.randomUUID();
        UUID staleId = UUID.randomUUID();
        insertDeviceCanonical(onlineId, orgA, "online-host", DeviceStatus.ONLINE);
        insertDeviceCanonical(offlineId, orgA, "offline-host", DeviceStatus.OFFLINE);
        insertDeviceCanonical(staleId, orgA, "stale-host", DeviceStatus.STALE);

        // Single-status filter excludes non-matching.
        List<EndpointDevice> onlineOnly = repository.findVisibleToOrgAndStatusIn(
                orgA, List.of(DeviceStatus.ONLINE));
        assertThat(onlineOnly)
                .extracting(EndpointDevice::getId).containsExactly(onlineId);

        // Multi-status IN set returns the union.
        List<EndpointDevice> onlineOrStale = repository.findVisibleToOrgAndStatusIn(
                orgA, List.of(DeviceStatus.ONLINE, DeviceStatus.STALE));
        assertThat(onlineOrStale)
                .extracting(EndpointDevice::getId)
                .containsExactlyInAnyOrder(onlineId, staleId);
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private void insertDeviceCanonical(UUID id, UUID org, String hostname, DeviceStatus status) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'WINDOWS', ?, ?, ?, 0)",
                id, org, org, hostname, status.name(), now, now);
    }

    private void insertDeviceLegacyNullOrg(UUID id, UUID tenant, String hostname, DeviceStatus status) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices DISABLE TRIGGER USER");
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                            + "(id, tenant_id, org_id, hostname, os_type, status, "
                            + " created_at, updated_at, version) "
                            + "VALUES (?, ?, NULL, ?, 'WINDOWS', ?, ?, ?, 0)",
                    id, tenant, hostname, status.name(), now, now);
        } finally {
            jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices ENABLE TRIGGER USER");
        }
    }
}

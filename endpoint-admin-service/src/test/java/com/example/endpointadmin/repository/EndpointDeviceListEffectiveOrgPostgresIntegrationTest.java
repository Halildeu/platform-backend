package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
 * Faz 21.1 PR2b-iv.b3 regression guard —
 * {@link EndpointDeviceRepository#findVisibleToOrgOrderByHostnameAsc}
 * canonical effective-org listing (Codex 019e8d1d B-B sub-slice b3
 * AGREE; P1 parenthesized OR pattern + hostname ASC).
 *
 * <p>The canonical predicate + ordering:
 * <pre>
 *   WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))
 *   ORDER BY d.hostname ASC
 * </pre>
 *
 * <p>Four behavioural assertions (Codex 019e8d1d b3 PG IT minimums):
 * <ol>
 *   <li>Canonical row listed.</li>
 *   <li>Legacy NULL row listed (V29 trigger bypassed + pre-assert).</li>
 *   <li>Cross-org rows absent (orgA list MUST NOT contain orgB devices).</li>
 *   <li>Hostname ASC ordering preserved across canonical + legacy mix.</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointDeviceListEffectiveOrgPostgresIntegrationTest {

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
    void canonicalRow_isListed() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, orgA, "canon-host");

        List<EndpointDevice> list = repository.findVisibleToOrgOrderByHostnameAsc(orgA);
        assertThat(list)
                .extracting(EndpointDevice::getId)
                .containsExactly(deviceId);
    }

    @Test
    void legacyNullRow_isListed_andPreservedAsNullByFixture() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceLegacyNullOrg(deviceId, orgA, "legacy-host");

        Boolean orgIdIsNull = jdbc.queryForObject(
                "SELECT org_id IS NULL FROM " + SCHEMA + ".endpoint_devices WHERE id = ?",
                Boolean.class, deviceId);
        assertThat(orgIdIsNull)
                .as("legacy NULL fixture pre-assert: V29 trigger bypass held")
                .isTrue();

        List<EndpointDevice> list = repository.findVisibleToOrgOrderByHostnameAsc(orgA);
        assertThat(list)
                .as("legacy NULL row listed via OR fallback branch")
                .extracting(EndpointDevice::getId)
                .containsExactly(deviceId);
    }

    @Test
    void crossOrg_listingForOrgA_excludesOrgBDevices() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        insertDeviceCanonical(deviceA, orgA, "orga-host");
        insertDeviceCanonical(deviceB, orgB, "orgb-host");

        List<EndpointDevice> listA = repository.findVisibleToOrgOrderByHostnameAsc(orgA);
        assertThat(listA)
                .as("orgA list contains its own device + does NOT contain orgB's")
                .extracting(EndpointDevice::getId)
                .containsExactly(deviceA);

        List<EndpointDevice> listB = repository.findVisibleToOrgOrderByHostnameAsc(orgB);
        assertThat(listB)
                .extracting(EndpointDevice::getId)
                .containsExactly(deviceB);
    }

    @Test
    void hostnameAscOrdering_preservedAcrossCanonicalAndLegacyMix() {
        UUID orgA = UUID.randomUUID();
        UUID legacyDeviceB = UUID.randomUUID();
        UUID canonicalDeviceA = UUID.randomUUID();
        UUID canonicalDeviceC = UUID.randomUUID();

        // Three devices in orgA: "alpha" (canonical), "bravo" (legacy NULL),
        // "charlie" (canonical). The OR fallback must not perturb the
        // hostname ASC ordering between canonical and legacy rows.
        insertDeviceCanonical(canonicalDeviceA, orgA, "alpha-host");
        insertDeviceLegacyNullOrg(legacyDeviceB, orgA, "bravo-host");
        insertDeviceCanonical(canonicalDeviceC, orgA, "charlie-host");

        List<EndpointDevice> list = repository.findVisibleToOrgOrderByHostnameAsc(orgA);
        assertThat(list)
                .as("ordering: alpha < bravo < charlie regardless of canonical/legacy mix")
                .extracting(EndpointDevice::getHostname)
                .containsExactly("alpha-host", "bravo-host", "charlie-host");
    }

    // ───────────────────────── Seed helpers ─────────────────────────

    private void insertDeviceCanonical(UUID id, UUID org, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type, status, "
                        + " created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, hostname, now, now);
    }

    private void insertDeviceLegacyNullOrg(UUID id, UUID tenant, String hostname) {
        Timestamp now = Timestamp.from(Instant.parse("2026-06-03T10:00:00Z"));
        jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices DISABLE TRIGGER USER");
        try {
            jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                            + "(id, tenant_id, org_id, hostname, os_type, status, "
                            + " created_at, updated_at, version) "
                            + "VALUES (?, ?, NULL, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                    id, tenant, hostname, now, now);
        } finally {
            jdbc.execute("ALTER TABLE " + SCHEMA + ".endpoint_devices ENABLE TRIGGER USER");
        }
    }
}

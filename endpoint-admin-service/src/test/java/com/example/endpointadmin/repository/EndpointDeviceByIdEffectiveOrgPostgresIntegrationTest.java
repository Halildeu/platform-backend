package com.example.endpointadmin.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.endpointadmin.model.EndpointDevice;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
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
 * Faz 21.1 PR2b-iv.b1 regression guard —
 * {@link EndpointDeviceRepository#findVisibleToOrgAndId} canonical
 * effective-org device-by-id ownership gate (Codex 019e8d1d B-B
 * sub-slice AGREE + P1 parenthesized OR pattern).
 *
 * <p>The canonical predicate:
 * <pre>
 *   WHERE (d.org_id = :orgId OR (d.org_id IS NULL AND d.tenant_id = :orgId))
 *     AND d.id = :id
 * </pre>
 *
 * <p>Three assertions on the b1 read-path correctness modes (Codex
 * 019e8d1d b1 PG IT minimums):
 * <ol>
 *   <li>Canonical row read — both columns equal, repository returns
 *       it via {@code findVisibleToOrgAndId}.</li>
 *   <li>Legacy NULL row read — V29 trigger temporarily disabled to
 *       seed {@code org_id IS NULL AND tenant_id = orgA}; pre-assert
 *       confirms persisted NULL; method returns it via fallback OR
 *       branch.</li>
 *   <li>Cross-org negative — orgA lookup for orgB's device returns
 *       empty (no existence leak, ownership gate preserved).</li>
 * </ol>
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class EndpointDeviceByIdEffectiveOrgPostgresIntegrationTest {

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
    void canonicalRow_bothColumnsEqual_isReturnedByEffectiveOrgFilter() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceCanonical(deviceId, orgA, "canonical-device");

        Optional<EndpointDevice> hit = repository.findVisibleToOrgAndId(orgA, deviceId);
        assertThat(hit).isPresent()
                .hasValueSatisfying(d -> {
                    assertThat(d.getId()).isEqualTo(deviceId);
                    assertThat(d.getHostname()).isEqualTo("canonical-device");
                });
    }

    @Test
    void legacyNullRow_orgIdNull_isReturnedViaTenantIdFallback_andPreservedAsNullByFixture() {
        UUID orgA = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        insertDeviceLegacyNullOrg(deviceId, orgA, "legacy-null-device");

        // Pre-assertion (Codex 019e8d1d b1 must-fix): V29 trigger bypass
        // must hold — if the row persisted with org_id auto-filled, the
        // OR fallback branch is not actually being tested.
        Boolean orgIdIsNull = jdbc.queryForObject(
                "SELECT org_id IS NULL FROM " + SCHEMA + ".endpoint_devices WHERE id = ?",
                Boolean.class, deviceId);
        assertThat(orgIdIsNull)
                .as("legacy NULL fixture pre-assert: org_id must persist as NULL "
                        + "(DISABLE TRIGGER USER held); otherwise the fallback OR "
                        + "branch isn't exercised")
                .isTrue();

        Optional<EndpointDevice> hit = repository.findVisibleToOrgAndId(orgA, deviceId);
        assertThat(hit)
                .as("legacy NULL row reachable via OR fallback branch")
                .isPresent()
                .hasValueSatisfying(d -> assertThat(d.getId()).isEqualTo(deviceId));
    }

    @Test
    void crossOrg_orgAFilter_doesNotReturnOrgBsDevice() {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID deviceA = UUID.randomUUID();
        UUID deviceB = UUID.randomUUID();
        insertDeviceCanonical(deviceA, orgA, "orga-device");
        insertDeviceCanonical(deviceB, orgB, "orgb-device");

        // orgA's lookup of its own device → present.
        Optional<EndpointDevice> hit = repository.findVisibleToOrgAndId(orgA, deviceA);
        assertThat(hit).isPresent();

        // orgA's lookup of orgB's device → empty (ownership gate enforced;
        // no existence leak — same empty Optional whether the device
        // doesn't exist at all or exists under another org).
        Optional<EndpointDevice> miss = repository.findVisibleToOrgAndId(orgA, deviceB);
        assertThat(miss)
                .as("orgA MUST NOT see orgB's device via the OR fallback "
                        + "(the parenthesized effective-org predicate keeps the "
                        + "tenant boundary; orgA's filter never matches orgB's "
                        + "org_id nor orgB's tenant_id)")
                .isEmpty();
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
        // Bypass V29 trigger so the row persists with org_id IS NULL
        // (a true pre-PR1 / legacy shape).
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

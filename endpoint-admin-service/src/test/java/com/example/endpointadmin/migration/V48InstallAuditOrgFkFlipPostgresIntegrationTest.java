package com.example.endpointadmin.migration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Faz 21.1 Cleanup C4 step-8 regression guard — V48 endpoint_install_audit 3 FK
 * flips (PURE-FLIP, the first hub-dependent consumer flip). install_audit is
 * ORG-DONE; this slice rebinds its device/command/catalog FKs to org-composite
 * once both hubs (V46 commands + V47 catalog) carry UNIQUE(id, org_id). Preserves
 * ON DELETE per edge: device CASCADE, command CASCADE, catalog RESTRICT.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V48InstallAuditOrgFkFlipPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String IA = SCHEMA + ".endpoint_install_audit";

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("endpoint_admin").withUsername("test").withPassword("test");

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

    private static final String[][] ORG_FKS = {
            {"install_audit_device_org_fk",
             "FOREIGN KEY (device_id, org_id) REFERENCES " + SCHEMA + ".endpoint_devices(id, org_id) ON DELETE CASCADE"},
            {"install_audit_command_org_fk",
             "FOREIGN KEY (command_id, org_id) REFERENCES " + SCHEMA + ".endpoint_commands(id, org_id) ON DELETE CASCADE"},
            {"install_audit_catalog_org_fk",
             "FOREIGN KEY (catalog_item_id, org_id) REFERENCES " + SCHEMA + ".endpoint_software_catalog_items(id, org_id) ON DELETE RESTRICT"}
    };

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void all3FksFlippedToOrgComposite_andValidated() {
        for (String[] fk : ORG_FKS) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='f'", Boolean.class, fk[0]))
                    .as("%s validated", fk[0]).isTrue();
            assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname=?", String.class, fk[0]))
                    .as("%s def", fk[0]).isEqualTo(fk[1]);
        }
        for (String old : new String[]{
                "fk_endpoint_install_audit_device",
                "fk_endpoint_install_audit_command",
                "fk_endpoint_install_audit_catalog"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=? AND contype='f'", Long.class, old))
                    .as("old tenant FK %s dropped", old).isEqualTo(0L);
        }
    }

    @Test
    void crossOrgInstallAuditInsert_isRejectedByDeviceOrgFk_23503() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID device = seedDevice(orgA);
        UUID command = seedCommand(orgA, device);
        UUID catalog = seedCatalog(orgA);
        // tenant_id=orgB (trigger fills org_id=orgB) but parents live in orgA →
        // the (device_id, org_id) composite FK has no (orgA_device, orgB) parent.
        assertThatThrownBy(() -> insertAudit(orgB, device, command, catalog))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void deviceDelete_cascadesInstallAudit() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org), command = seedCommand(org, device), catalog = seedCatalog(org);
        UUID audit = insertAudit(org, device, command, catalog);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_devices WHERE id=?", device);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + IA + " WHERE id=?", Long.class, audit)).isEqualTo(0L);
    }

    @Test
    void commandDelete_cascadesInstallAudit() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org), command = seedCommand(org, device), catalog = seedCatalog(org);
        UUID audit = insertAudit(org, device, command, catalog);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_commands WHERE id=?", command);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + IA + " WHERE id=?", Long.class, audit)).isEqualTo(0L);
    }

    @Test
    void catalogDelete_isRestrictedByInstallAuditCatalogOrgFk_23503() {
        UUID org = UUID.randomUUID();
        UUID device = seedDevice(org), command = seedCommand(org, device), catalog = seedCatalog(org);
        insertAudit(org, device, command, catalog);
        // RESTRICT preserved: a catalog item with install history cannot be deleted.
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_software_catalog_items WHERE id=?", catalog))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    // ───────────────────────── helpers ─────────────────────────

    private UUID seedDevice(UUID org) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, machine_fingerprint, os_type, status, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, ?, ?, 'WINDOWS', 'ONLINE', ?, ?, 0)",
                id, org, org, "host-" + id, "fp-" + id, now, now);
        return id;
    }

    /** org_id omitted — V46 trigger fills it from tenant_id. */
    private UUID seedCommand(UUID org, UUID device) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_commands "
                        + "(id, tenant_id, device_id, command_type, idempotency_key, issued_by_subject, issued_at, created_at, updated_at, version) "
                        + "VALUES (?, ?, ?, 'INSTALL_SOFTWARE', ?, 'issuer', ?, ?, ?, 0)",
                id, org, device, "idem-" + id, now, now, now);
        return id;
    }

    /** org_id omitted — V47 trigger fills it from tenant_id. */
    private UUID seedCatalog(UUID org) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + SCHEMA + ".endpoint_software_catalog_items ("
                        + "id, tenant_id, catalog_item_id, status, provider, source_type, source_name,"
                        + " source_trust, package_id, display_name, publisher, version_policy_type,"
                        + " detection_rule, risk_tier, enabled, created_by_subject, created_at,"
                        + " last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'APPROVED', 'WINGET', 'WINGET', 'winget',"
                        + " 'WINGET_COMMUNITY_REVIEWED', ?, ?, 'Publisher', 'LATEST',"
                        + " '{\"type\":\"WINGET_PACKAGE\"}'::jsonb, 'LOW', true,"
                        + " 'creator', ?, 'creator', ?, 0)",
                id, org, "item-" + id, "pkg-" + id, "Display-" + id, now, now);
        return id;
    }

    /** org_id omitted — V29 trigger fills it from tenant_id (= org here). */
    private UUID insertAudit(UUID org, UUID device, UUID command, UUID catalog) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + IA + " ("
                        + "id, tenant_id, device_id, command_id, catalog_item_id, catalog_package_id,"
                        + " catalog_row_version, preflight_decision, preflight_decision_at, actor_subject,"
                        + " result_status, reported_at, post_verification) "
                        + "VALUES (?, ?, ?, ?, ?, ?, 0, 'PASS', ?, 'actor', 'SUCCEEDED', ?, 'SATISFIED')",
                id, org, device, command, catalog, "pkg-" + catalog, now, now);
        return id;
    }

    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) return sqlEx.getSQLState();
            cur = cur.getCause();
        }
        return null;
    }
}

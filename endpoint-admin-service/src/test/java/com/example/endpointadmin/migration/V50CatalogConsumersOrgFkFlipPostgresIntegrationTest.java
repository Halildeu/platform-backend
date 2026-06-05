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
 * Faz 21.1 Cleanup C4 step-10 regression guard — V50 remaining 2 catalog-consumer
 * FK flips (foundation + flip). Completes the board #469 12-FK arc:
 * endpoint_software_compliance_policy_items (CASCADE + business unique swap) +
 * catalog_uninstall_settings_change_requests (NO ACTION). Both FK only to the
 * V47 catalog hub.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V50CatalogConsumersOrgFkFlipPostgresIntegrationTest {

    private static final String SCHEMA = "endpoint_admin_service";
    private static final String CPI = SCHEMA + ".endpoint_software_compliance_policy_items";
    private static final String CUSCR = SCHEMA + ".catalog_uninstall_settings_change_requests";

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

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void bothFksFlippedToOrgComposite_andValidated() {
        assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname='compliance_policy_items_catalog_org_fk' AND contype='f'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='compliance_policy_items_catalog_org_fk'", String.class))
                .isEqualTo("FOREIGN KEY (catalog_item_id, org_id) REFERENCES " + SCHEMA + ".endpoint_software_catalog_items(id, org_id) ON DELETE CASCADE");
        assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname='cuscr_catalog_org_fk' AND contype='f'", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='cuscr_catalog_org_fk'", String.class))
                .isEqualTo("FOREIGN KEY (catalog_item_id, org_id) REFERENCES " + SCHEMA + ".endpoint_software_catalog_items(id, org_id)");
        for (String old : new String[]{"fk_endpoint_software_compliance_policy_items_catalog", "fk_catalog_unins_change_catalog"}) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname=? AND contype='f'", Long.class, old))
                    .as("old FK %s dropped", old).isEqualTo(0L);
        }
    }

    @Test
    void orgChecksValidated_andCompliancesBusinessUniqueSwapped() {
        for (String con : new String[]{
                "endpoint_compliance_policy_items_org_id_match", "endpoint_compliance_policy_items_org_id_not_null",
                "catalog_unins_change_org_id_match", "catalog_unins_change_org_id_not_null"}) {
            assertThat(jdbc.queryForObject("SELECT convalidated FROM pg_constraint WHERE conname=? AND contype='c'", Boolean.class, con))
                    .as("%s validated", con).isTrue();
        }
        assertThat(jdbc.queryForObject(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname='uq_endpoint_software_compliance_policy_items_org_catalog' AND contype='u'", String.class))
                .isEqualTo("UNIQUE (org_id, catalog_item_id)");
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint WHERE conname='uq_endpoint_software_compliance_policy_items_tenant_catalog'", Long.class))
                .as("old tenant business unique dropped").isEqualTo(0L);
    }

    @Test
    void legacyWriter_orgIdOmitted_filledByTriggers() {
        UUID org = UUID.randomUUID();
        UUID catalog = seedCatalog(org);
        UUID cpi = insertCompliance(org, catalog);
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + CPI + " WHERE id=?", UUID.class, cpi)).isEqualTo(org);
        UUID cuscr = insertCuscr(org, catalog);
        assertThat(jdbc.queryForObject("SELECT org_id FROM " + CUSCR + " WHERE id=?", UUID.class, cuscr)).isEqualTo(org);
    }

    @Test
    void crossOrgComplianceInsert_isRejected_23503() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID catalog = seedCatalog(orgA);
        assertThatThrownBy(() -> insertCompliance(orgB, catalog))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void crossOrgCuscrInsert_isRejected_23503() {
        UUID orgA = UUID.randomUUID(), orgB = UUID.randomUUID();
        UUID catalog = seedCatalog(orgA);
        assertThatThrownBy(() -> insertCuscr(orgB, catalog))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void catalogDelete_cascadesCompliance_butRestrictedByCuscr() {
        // compliance: CASCADE — deleting the catalog removes the policy item.
        UUID org1 = UUID.randomUUID();
        UUID cat1 = seedCatalog(org1);
        UUID cpi = insertCompliance(org1, cat1);
        jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_software_catalog_items WHERE id=?", cat1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM " + CPI + " WHERE id=?", Long.class, cpi)).isEqualTo(0L);

        // cuscr: NO ACTION — a catalog with an open change-request cannot be deleted.
        UUID org2 = UUID.randomUUID();
        UUID cat2 = seedCatalog(org2);
        insertCuscr(org2, cat2);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM " + SCHEMA + ".endpoint_software_catalog_items WHERE id=?", cat2))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23503"));
    }

    @Test
    void cuscrOneOpenPartialUnique_swappedToOrgKeyed_andEnforced() {
        String def = jdbc.queryForObject(
                "SELECT pg_get_indexdef(indexrelid) FROM pg_index i JOIN pg_class c ON c.oid=i.indexrelid WHERE c.relname='uq_catalog_unins_change_one_open'", String.class);
        assertThat(def).contains("org_id").doesNotContain("tenant_id");

        UUID org = UUID.randomUUID();
        UUID catalog = seedCatalog(org);
        UUID open = insertCuscr(org, catalog);
        assertThat(open).isNotNull();
        // A second open (PROPOSED) request for the same (org, catalog, field) is
        // rejected by the org-keyed one-open partial unique. (The APPLIED/REJECTED
        // terminal-state exemption is covered by the full-chain V31 migration IT,
        // which now exercises this org-keyed index.)
        assertThatThrownBy(() -> insertCuscr(org, catalog))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    @Test
    void sameOrgDuplicateCompliancePolicy_isRejectedByOrgArbiter_23505() {
        UUID org = UUID.randomUUID();
        UUID catalog = seedCatalog(org);
        insertCompliance(org, catalog);
        // Same (org_id, catalog_item_id): the org-keyed business unique rejects it.
        assertThatThrownBy(() -> insertCompliance(org, catalog))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(t -> assertThat(rootSqlState(t)).isEqualTo("23505"));
    }

    // ───────────────────────── helpers ─────────────────────────

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

    /** org_id omitted — V50 trigger fills it from tenant_id. */
    private UUID insertCompliance(UUID org, UUID catalog) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + CPI + " "
                        + "(id, tenant_id, catalog_item_id, enforcement_mode, enabled, created_by_subject,"
                        + " created_at, last_updated_by_subject, last_updated_at, version) "
                        + "VALUES (?, ?, ?, 'REQUIRED', true, 'creator', ?, 'creator', ?, 0)",
                id, org, catalog, now, now);
        return id;
    }

    /** org_id omitted — V50 trigger fills it from tenant_id. PROPOSED satisfies the state-pairing CHECK. */
    private UUID insertCuscr(UUID org, UUID catalog) {
        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.update("INSERT INTO " + CUSCR + " "
                        + "(id, tenant_id, catalog_item_id, field, new_value, proposed_by, proposed_at, state, created_at) "
                        + "VALUES (?, ?, ?, 'UNINSTALL_SUPPORTED', true, 'proposer', ?, 'PROPOSED', ?)",
                id, org, catalog, now, now);
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

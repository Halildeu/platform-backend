package com.example.endpointadmin.migration;

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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Postgres-only verification of V29 (Faz 21.1 org_id compat layer).
 *
 * <p>Asserts the migration outcome on each of the 7 tenant-scoped endpoint
 * tables flagged by the PR-5 dry-run evidence
 * ({@code docs/faz-23-evidence/2026-06-03-faz-21-dryrun-on-test-cluster.md}):
 *
 * <ol>
 *     <li>{@code org_id} UUID column exists (NULLABLE in PR1)</li>
 *     <li>Index on {@code org_id} exists</li>
 *     <li>BEFORE INSERT/UPDATE trigger exists for the compat fill</li>
 *     <li>Trigger smoke: legacy writer inserting only {@code tenant_id} → trigger
 *         auto-fills {@code org_id = tenant_id}</li>
 *     <li>Trigger no-op: explicit {@code org_id} write does NOT get overwritten</li>
 * </ol>
 *
 * <p>Codex 019e8c95 plan-time AGREE — Faz 21.1 PR1 binding deliverable. Code-
 * side dual-read COALESCE + canonical org_id write moves to PR2. Drop of
 * {@code tenant_id} moves to cleanup PR after at least one deploy/rollback
 * window with mismatch=0 evidence.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V29OrgIdCompatLayerPostgresIntegrationTest {

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
        registry.add("spring.flyway.default-schema", () -> "public");
        registry.add("spring.flyway.schemas", () -> "public");
        registry.add("spring.jpa.properties.hibernate.default_schema",
                () -> "public");
    }

    private static final String[] TARGET_TABLES = {
            "endpoint_devices",
            "endpoint_software_inventory_state_history",
            "endpoint_outdated_software_snapshots",
            "endpoint_outdated_software_packages",
            "endpoint_install_audit",
            "endpoint_compliance_evaluations",
            "endpoint_app_control_snapshots"
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allSevenTablesHaveOrgIdColumn() {
        for (String table : TARGET_TABLES) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.columns "
                            + "WHERE table_schema = 'public' AND table_name = ? "
                            + "AND column_name = 'org_id' AND data_type = 'uuid'",
                    Long.class, table);
            assertThat(count)
                    .as("Table %s must have org_id UUID column", table)
                    .isEqualTo(1L);
        }
    }

    @Test
    void allSevenTablesHaveOrgIdIndex() {
        for (String table : TARGET_TABLES) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_indexes "
                            + "WHERE schemaname = 'public' AND tablename = ? "
                            + "AND indexdef LIKE '%(org_id)%'",
                    Long.class, table);
            assertThat(count)
                    .as("Table %s must have an index on org_id", table)
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void allSevenTablesHaveCompatTrigger() {
        for (String table : TARGET_TABLES) {
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM information_schema.triggers "
                            + "WHERE event_object_schema = 'public' "
                            + "AND event_object_table = ? "
                            + "AND action_statement LIKE '%endpoint_org_id_compat_fill%'",
                    Long.class, table);
            assertThat(count)
                    .as("Table %s must have endpoint_org_id_compat_fill trigger", table)
                    .isGreaterThanOrEqualTo(1L);
        }
    }

    @Test
    void compatFillFunctionExists() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_proc WHERE proname = 'endpoint_org_id_compat_fill'",
                Long.class);
        assertThat(count).isEqualTo(1L);
    }

    @Test
    void backfillProducesNoMismatch() {
        // V29's terminal DO block raised an exception if any backfill mismatch
        // remained. If Flyway succeeded (Spring boot context loaded with
        // ddl-auto=validate + flyway enabled = true), this test exists to make
        // that contract explicit in the test surface; re-verify here that no
        // legacy row has tenant_id != org_id post-migration on any target table.
        for (String table : TARGET_TABLES) {
            Long mismatchCount = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM " + table
                            + " WHERE tenant_id IS NOT NULL "
                            + "AND (org_id IS NULL OR org_id != tenant_id)",
                    Long.class);
            assertThat(mismatchCount)
                    .as("Table %s must have zero tenant_id/org_id mismatch", table)
                    .isZero();
        }
    }

    // ============================================================
    // Codex iter-1 P1 absorb — live DML trigger behavior tests.
    // ============================================================

    @Test
    void legacyInsertWithOnlyTenantId_triggerFillsOrgId() {
        // Insert a fresh endpoint_devices row with tenant_id only; trigger
        // must auto-fill org_id from tenant_id.
        var tenantId = java.util.UUID.randomUUID();
        var hostname = "v29-trigger-legacy-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'LINUX')",
                tenantId, hostname);

        var orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                java.util.UUID.class, hostname);

        assertThat(orgId)
                .as("Legacy insert with only tenant_id must auto-fill org_id")
                .isEqualTo(tenantId);
    }

    @Test
    void explicitDualWriteEqual_preservesOrgId() {
        // Writer supplies both tenant_id and org_id with the same value;
        // trigger should be a no-op (org_id already set).
        var tenantId = java.util.UUID.randomUUID();
        var hostname = "v29-trigger-dual-equal-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, 'LINUX')",
                tenantId, tenantId, hostname);

        var orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                java.util.UUID.class, hostname);

        assertThat(orgId)
                .as("Explicit equal dual-write must preserve org_id (trigger no-op)")
                .isEqualTo(tenantId);
    }

    @Test
    void explicitDualWriteMismatch_headStateBehaviorPostV30() {
        // Codex iter-1 F4 absorb: this test runs against Flyway head state
        // (V29 + V30 both applied). The canonical reject assertion lives in
        // V30OrgIdCheckConstraintPostgresIntegrationTest. This test
        // documents that the V29 trigger by itself (purely additive) is NOT
        // sufficient to enforce equality — only the V30 CHECK constraint is.
        // At Flyway head, mismatch reject is observable via this test as a
        // sanity smoke; the binding assertion lives with V30.
        var tenantId = java.util.UUID.randomUUID();
        var orgIdInput = java.util.UUID.randomUUID();
        var hostname = "v29-trigger-dual-mismatch-" + tenantId;

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, 'LINUX')",
                tenantId, orgIdInput, hostname))
                .as("Head-state: V29 trigger alone NOT sufficient; V30 CHECK rejects mismatch (canonical assertion in V30 test)")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    void updateClearingOrgId_triggerRefillsOnNextWrite() {
        // Set org_id back to null, then UPDATE another column; trigger fires
        // BEFORE UPDATE → refills org_id from tenant_id.
        var tenantId = java.util.UUID.randomUUID();
        var hostname = "v29-trigger-update-refill-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'LINUX')",
                tenantId, hostname);

        // Force org_id back to NULL via direct SQL (bypassing trigger semantics
        // is not possible since the trigger fires on UPDATE; the IF guard
        // protects this case — UPDATE SET org_id=NULL fires trigger which then
        // sees org_id IS NULL and fills it again from tenant_id).
        jdbcTemplate.update(
                "UPDATE endpoint_devices SET org_id = NULL WHERE hostname = ?",
                hostname);

        var orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                java.util.UUID.class, hostname);

        assertThat(orgId)
                .as("UPDATE SET org_id=NULL must re-trigger fill from tenant_id (compat layer invariant)")
                .isEqualTo(tenantId);
    }
}

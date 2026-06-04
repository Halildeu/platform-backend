package com.example.endpointadmin.migration;

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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Postgres-only verification of V34 (Faz 21.1 Cleanup C1 — Source Org-Key
 * Foundation), the bounded migration Codex 019e919e PARTIAL-approved.
 *
 * <p>V34 does exactly two additive things; this test machine-enforces both,
 * plus the non-breaking guarantee:
 * <ol>
 *     <li>(A) Non-null evidence gate: all 9 org_id-bearing endpoint tables
 *         carry {@code CHECK (org_id IS NOT NULL)} AND it is VALIDATED
 *         ({@code pg_constraint.convalidated = true}).</li>
 *     <li>(A) Behavioral proof: with the V29 compat trigger disabled, an
 *         insert with {@code org_id = NULL} is REJECTED with SQLSTATE 23514
 *         (PG check_violation) — the constraint actually bites.</li>
 *     <li>(B) FK-target enabler: the 3 cache parents (endpoint_devices,
 *         endpoint_software_inventory_state_history,
 *         endpoint_outdated_software_snapshots) carry
 *         {@code UNIQUE (id, org_id)}.</li>
 *     <li>Non-breaking: a canonical insert (trigger fills org_id) still
 *         succeeds, and the pre-existing PK(id) + UNIQUE(id, tenant_id) on
 *         endpoint_devices are intact (V34 is purely additive).</li>
 * </ol>
 *
 * <p>This migration intentionally does NOT rewrite FKs, remove effective-org
 * fallback reads, or drop tenant_id (see V34 header boundary sentence). Those
 * are out of scope and out of this test.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V34OrgIdSourceFoundationPostgresIntegrationTest {

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

    /** All 9 org_id-bearing tables + their V34 non-null CHECK constraint name. */
    private static final String[][] NON_NULL_CHECKS = {
            {"endpoint_devices", "endpoint_devices_org_id_not_null"},
            {"endpoint_software_inventory_state_history", "endpoint_sw_inv_state_org_id_not_null"},
            {"endpoint_outdated_software_snapshots", "endpoint_outdated_sw_snap_org_id_not_null"},
            {"endpoint_outdated_software_packages", "endpoint_outdated_sw_pkg_org_id_not_null"},
            {"endpoint_install_audit", "endpoint_install_audit_org_id_not_null"},
            {"endpoint_compliance_evaluations", "endpoint_compliance_eval_org_id_not_null"},
            {"endpoint_app_control_snapshots", "endpoint_app_control_snap_org_id_not_null"},
            {"endpoint_software_diff_cache", "swdc_org_id_not_null"},
            {"endpoint_outdated_software_diff_cache", "osdc_org_id_not_null"}
    };

    /** The 3 cache parents + their V34 UNIQUE (id, org_id) constraint name. */
    private static final String[][] UNIQUE_PARENTS = {
            {"endpoint_devices", "endpoint_devices_id_org_id_key"},
            {"endpoint_software_inventory_state_history", "endpoint_sw_inv_state_id_org_id_key"},
            {"endpoint_outdated_software_snapshots", "endpoint_outdated_sw_snap_id_org_id_key"}
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allNineTablesHaveNonNullCheckConstraint() {
        for (String[] row : NON_NULL_CHECKS) {
            String table = row[0];
            String constraint = row[1];
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_constraint c "
                            + "JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = ? AND c.conname = ? AND c.contype = 'c'",
                    Long.class, table, constraint);
            assertThat(count)
                    .as("Table %s must have non-null CHECK constraint %s", table, constraint)
                    .isEqualTo(1L);
        }
    }

    @Test
    void allNineNonNullChecksAreValidated() {
        // VALIDATE CONSTRAINT (V34 Phase 2) sets convalidated = true. A
        // NOT VALID-only constraint would be false → the evidence gate would
        // not be the machine-enforced precondition it claims to be.
        for (String[] row : NON_NULL_CHECKS) {
            String table = row[0];
            String constraint = row[1];
            Boolean validated = jdbcTemplate.queryForObject(
                    "SELECT c.convalidated FROM pg_constraint c "
                            + "JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = ? AND c.conname = ?",
                    Boolean.class, table, constraint);
            assertThat(validated)
                    .as("Constraint %s on %s must be VALIDATED (V34 Phase 2)", constraint, table)
                    .isTrue();
        }
    }

    @Test
    void allNineNonNullCheckExpressionsAreCorrect() {
        // Guard against constraint-body drift: the canonical SQL form must
        // reference "org_id IS NOT NULL", not some weaker predicate.
        for (String[] row : NON_NULL_CHECKS) {
            String table = row[0];
            String constraint = row[1];
            String definition = jdbcTemplate.queryForObject(
                    "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                            + "JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = ? AND c.conname = ?",
                    String.class, table, constraint);
            assertThat(definition)
                    .as("Constraint %s on %s body must be CHECK (org_id IS NOT NULL)", constraint, table)
                    .containsIgnoringCase("org_id IS NOT NULL");
        }
    }

    @Test
    void threeCacheParentsHaveIdOrgIdUniqueConstraint() {
        // C2 cache org-key flip recreates FKs (child_col, org_id) ->
        // parent(id, org_id); that requires a UNIQUE on exactly (id, org_id).
        for (String[] row : UNIQUE_PARENTS) {
            String table = row[0];
            String constraint = row[1];
            String definition = jdbcTemplate.queryForObject(
                    "SELECT pg_get_constraintdef(c.oid) FROM pg_constraint c "
                            + "JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = ? AND c.conname = ? AND c.contype = 'u'",
                    String.class, table, constraint);
            assertThat(definition)
                    .as("Table %s must have UNIQUE (id, org_id) constraint %s", table, constraint)
                    .isNotNull()
                    .containsIgnoringCase("UNIQUE")
                    .containsIgnoringCase("id")
                    .containsIgnoringCase("org_id");
        }
    }

    @Test
    void nullOrgIdInsert_isRejectedByCheckConstraint_whenTriggerDisabled() {
        // The V29 compat trigger fills org_id from tenant_id, so a normal
        // insert can never carry org_id NULL. Disable the trigger surgically
        // to prove the CHECK itself rejects NULL with SQLSTATE 23514. This is
        // its own @Test so the constraint-violation-aborted transaction is
        // rolled back (which also restores the disabled trigger) by
        // @DataJpaTest at method end — no manual re-enable needed.
        UUID tenantId = UUID.randomUUID();
        String hostname = "v34-null-orgid-" + tenantId;

        jdbcTemplate.execute(
                "ALTER TABLE endpoint_devices DISABLE TRIGGER endpoint_devices_org_id_compat");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, NULL, ?, 'LINUX')",
                tenantId, hostname))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(throwable -> assertThat(rootSqlState(throwable))
                        .as("org_id NULL must be rejected with SQLSTATE 23514 (PG check_violation)")
                        .isEqualTo("23514"));
    }

    @Test
    void canonicalInsert_stillSucceeds_v34IsNonBreaking() {
        // Legacy writer (tenant_id only): V29 trigger fills org_id; both V30
        // (org_id = tenant_id) and V34 (org_id IS NOT NULL) CHECKs pass.
        UUID tenantId = UUID.randomUUID();
        String hostname = "v34-canonical-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'LINUX')",
                tenantId, hostname);

        UUID orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        assertThat(orgId)
                .as("canonical insert must still succeed and org_id = tenant_id (non-breaking)")
                .isEqualTo(tenantId);
    }

    @Test
    void preExistingDeviceConstraints_areIntact_v34IsAdditive() {
        // V34 must not disturb the pre-existing PK(id) + UNIQUE(id, tenant_id)
        // on endpoint_devices; the cleanup is additive only.
        Long pkCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON c.conrelid = t.oid "
                        + "WHERE t.relname = 'endpoint_devices' AND c.contype = 'p' "
                        + "AND pg_get_constraintdef(c.oid) = 'PRIMARY KEY (id)'",
                Long.class);
        assertThat(pkCount)
                .as("endpoint_devices PRIMARY KEY (id) must still exist after V34")
                .isEqualTo(1L);

        Long uniqueIdTenantCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM pg_constraint c "
                        + "JOIN pg_class t ON c.conrelid = t.oid "
                        + "WHERE t.relname = 'endpoint_devices' AND c.contype = 'u' "
                        + "AND pg_get_constraintdef(c.oid) = 'UNIQUE (id, tenant_id)'",
                Long.class);
        assertThat(uniqueIdTenantCount)
                .as("endpoint_devices UNIQUE (id, tenant_id) must still exist after V34")
                .isEqualTo(1L);
    }

    /**
     * Traverse to the root SQLException and return its SQLSTATE. Spring wraps
     * the driver error; the canonical PG check_violation code is 23514.
     */
    private static String rootSqlState(Throwable throwable) {
        Throwable cur = throwable;
        while (cur != null) {
            if (cur instanceof java.sql.SQLException sqlEx) {
                return sqlEx.getSQLState();
            }
            cur = cur.getCause();
        }
        return null;
    }
}

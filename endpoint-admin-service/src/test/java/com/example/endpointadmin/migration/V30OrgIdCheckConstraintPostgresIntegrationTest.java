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
 * Postgres-only verification of V30 (Faz 21.1 PR2a — org_id CHECK constraint).
 *
 * <p>Asserts that:
 * <ol>
 *     <li>All 7 tenant-scoped endpoint tables have the CHECK constraint
 *         {@code CHECK (org_id IS NULL OR org_id = tenant_id)}</li>
 *     <li>The constraint is marked VALIDATED (Postgres
 *         {@code pg_constraint.convalidated = true}) — V30 Phase 2 ran</li>
 *     <li>Legacy writer (tenant_id only, trigger fills org_id) passes</li>
 *     <li>Canonical writer (both columns equal) passes</li>
 *     <li>Mismatch writer (both columns different) is REJECTED with
 *         {@link DataIntegrityViolationException} (PG SQLSTATE 23514)</li>
 * </ol>
 *
 * <p>Codex 019e8ca1 plan-time AGREE Option B — PR2a scope is DB invariant
 * only; code-side dual-read COALESCE + canonical org_id write moves to PR2b.
 */
@Testcontainers
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class V30OrgIdCheckConstraintPostgresIntegrationTest {

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

    private static final String[][] TABLE_AND_CONSTRAINT = {
            {"endpoint_devices", "endpoint_devices_org_id_tenant_id_match"},
            {"endpoint_software_inventory_state_history", "endpoint_sw_inv_state_org_id_match"},
            {"endpoint_outdated_software_snapshots", "endpoint_outdated_sw_snap_org_id_match"},
            {"endpoint_outdated_software_packages", "endpoint_outdated_sw_pkg_org_id_match"},
            {"endpoint_install_audit", "endpoint_install_audit_org_id_match"},
            {"endpoint_compliance_evaluations", "endpoint_compliance_eval_org_id_match"},
            {"endpoint_app_control_snapshots", "endpoint_app_control_snap_org_id_match"}
    };

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void allSevenTablesHaveCheckConstraint() {
        for (String[] row : TABLE_AND_CONSTRAINT) {
            String table = row[0];
            String constraint = row[1];
            Long count = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM pg_constraint c "
                            + "JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = ? AND c.conname = ? AND c.contype = 'c'",
                    Long.class, table, constraint);
            assertThat(count)
                    .as("Table %s must have CHECK constraint %s", table, constraint)
                    .isEqualTo(1L);
        }
    }

    @Test
    void allSevenConstraintsAreValidated() {
        // Postgres pg_constraint.convalidated = true after VALIDATE CONSTRAINT
        // step (V30 Phase 2). NOT VALID-only constraints would have false.
        for (String[] row : TABLE_AND_CONSTRAINT) {
            String table = row[0];
            String constraint = row[1];
            Boolean validated = jdbcTemplate.queryForObject(
                    "SELECT c.convalidated FROM pg_constraint c "
                            + "JOIN pg_class t ON c.conrelid = t.oid "
                            + "WHERE t.relname = ? AND c.conname = ?",
                    Boolean.class, table, constraint);
            assertThat(validated)
                    .as("Constraint %s on %s must be VALIDATED (V30 Phase 2)", constraint, table)
                    .isTrue();
        }
    }

    @Test
    void legacyInsertWithTenantIdOnly_passesCheckViaTrigger() {
        // V29 trigger fills org_id from tenant_id → CHECK passes (org_id = tenant_id).
        UUID tenantId = UUID.randomUUID();
        String hostname = "v30-legacy-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'LINUX')",
                tenantId, hostname);

        UUID orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        assertThat(orgId).isEqualTo(tenantId);
    }

    @Test
    void canonicalInsertWithBothColumnsEqual_passesCheck() {
        // New writer pattern: supply both org_id + tenant_id with same UUID.
        UUID tenantId = UUID.randomUUID();
        String hostname = "v30-canonical-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, 'LINUX')",
                tenantId, tenantId, hostname);

        UUID orgId = jdbcTemplate.queryForObject(
                "SELECT org_id FROM endpoint_devices WHERE hostname = ?",
                UUID.class, hostname);
        assertThat(orgId).isEqualTo(tenantId);
    }

    @Test
    void mismatchInsertWithDifferentColumns_isRejectedByCheckConstraint() {
        // V29 PR1 documented drift case (Codex iter-1 P1); V30 binding
        // upgrade now REJECTS this with SQLSTATE 23514 (check_violation).
        UUID tenantId = UUID.randomUUID();
        UUID orgIdMismatch = UUID.randomUUID();
        String hostname = "v30-mismatch-" + tenantId;

        assertThatThrownBy(() -> jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, org_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, ?, 'LINUX')",
                tenantId, orgIdMismatch, hostname))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(throwable -> {
                    // PostgreSQL check_violation SQLSTATE = 23514
                    String message = throwable.getCause() != null
                            ? throwable.getCause().getMessage()
                            : throwable.getMessage();
                    assertThat(message)
                            .as("Mismatch must be rejected by org_id_tenant_id_match check constraint")
                            .containsAnyOf("org_id_tenant_id_match", "org_id_match");
                });
    }

    @Test
    void mismatchUpdateAttemptingToBreakInvariant_isRejected() {
        // Insert valid row, then UPDATE attempting to break invariant
        // (set org_id to different value than tenant_id) — must reject.
        UUID tenantId = UUID.randomUUID();
        UUID badOrgId = UUID.randomUUID();
        String hostname = "v30-update-mismatch-" + tenantId;

        jdbcTemplate.update(
                "INSERT INTO endpoint_devices "
                        + "(id, tenant_id, hostname, os_type) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'LINUX')",
                tenantId, hostname);

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE endpoint_devices SET org_id = ? WHERE hostname = ?",
                badOrgId, hostname))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}

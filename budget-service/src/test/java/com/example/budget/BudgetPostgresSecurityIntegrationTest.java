package com.example.budget;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class BudgetPostgresSecurityIntegrationTest {
    private static final String SCHEMA = "budget_service";
    private static final String APP_USER = "budget_app";
    private static final String APP_PASSWORD = "synthetic-budget-test-password";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("budget")
                    .withUsername("budget_migrator")
                    .withPassword("synthetic-migration-test-password");

    @BeforeAll
    static void migrateAndCreateRuntimeRole() throws SQLException {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .table("budget_flyway_history")
                .locations("classpath:db/migration", "classpath:db/vendor/postgresql")
                .load()
                .migrate();
        try (Connection connection = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
             var statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + APP_USER + " LOGIN PASSWORD '" + APP_PASSWORD + "' NOSUPERUSER NOBYPASSRLS");
            statement.execute("GRANT USAGE ON SCHEMA " + SCHEMA + " TO " + APP_USER);
            statement.execute("GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA "
                    + SCHEMA + " TO " + APP_USER);
        }
    }

    @Test
    void rlsFailsClosedAndApprovedLinesRemainImmutable() throws Exception {
        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID lineId = UUID.randomUUID();
        try (Connection connection = appConnection()) {
            assertThat(count(connection, "SELECT COUNT(*) FROM budget_plans")).isZero();

            connection.setAutoCommit(false);
            setTenant(connection, "tenant-a");
            execute(connection, """
                    INSERT INTO budget_plans
                      (id, tenant_id, company_id, fiscal_year, base_currency, created_by, created_at)
                    VALUES (?, 'tenant-a', 35, 2026, 'TRY', 'editor-1', ?)
                    """, planId, OffsetDateTime.now());
            execute(connection, """
                    INSERT INTO budget_versions
                      (id, plan_id, tenant_id, version_no, status, created_by, created_at)
                    VALUES (?, ?, 'tenant-a', 1, 'DRAFT', 'editor-1', ?)
                    """, versionId, planId, OffsetDateTime.now());
            execute(connection, """
                    INSERT INTO budget_lines
                      (id, version_id, tenant_id, period_start, account_code, direction,
                       planned_amount, currency)
                    VALUES (?, ?, 'tenant-a', DATE '2026-01-01', '740.01', 'EXPENSE', 1000, 'TRY')
                    """, lineId, versionId);
            execute(connection, """
                    UPDATE budget_versions
                       SET status='SUBMITTED', submitted_by='editor-1', submitted_at=?
                     WHERE id=?
                    """, OffsetDateTime.now(), versionId);
            execute(connection, """
                    UPDATE budget_versions
                       SET status='APPROVED', approved_by='approver-2', approved_at=?
                     WHERE id=?
                    """, OffsetDateTime.now(), versionId);
            connection.commit();

            connection.setAutoCommit(false);
            setTenant(connection, "tenant-a");
            assertThatThrownBy(() -> execute(connection,
                    "UPDATE budget_lines SET planned_amount=1 WHERE id=?", lineId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("editable only in DRAFT");
            connection.rollback();
        }

        try (Connection otherTenant = appConnection()) {
            otherTenant.setAutoCommit(false);
            setTenant(otherTenant, "tenant-b");
            assertThat(count(otherTenant, "SELECT COUNT(*) FROM budget_plans")).isZero();
            assertThatThrownBy(() -> execute(otherTenant, """
                    INSERT INTO budget_plans
                      (id, tenant_id, company_id, fiscal_year, base_currency, created_by, created_at)
                    VALUES (?, 'tenant-a', 35, 2028, 'TRY', 'cross-tenant', ?)
                    """, UUID.randomUUID(), OffsetDateTime.now()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            otherTenant.rollback();
        }
    }

    @Test
    void allocationLedgerRejectsOverAllocationAndRegistryFixes121() throws Exception {
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            setTenant(connection, "tenant-allocation");
            UUID planId = UUID.randomUUID();
            UUID versionId = UUID.randomUUID();
            UUID lineA = UUID.randomUUID();
            UUID lineB = UUID.randomUUID();
            UUID actualId = UUID.randomUUID();
            execute(connection, """
                    INSERT INTO budget_plans
                      (id, tenant_id, company_id, fiscal_year, base_currency, created_by, created_at)
                    VALUES (?, 'tenant-allocation', 35, 2026, 'TRY', 'editor', ?)
                    """, planId, OffsetDateTime.now());
            execute(connection, """
                    INSERT INTO budget_versions
                      (id, plan_id, tenant_id, version_no, status, created_by, created_at)
                    VALUES (?, ?, 'tenant-allocation', 1, 'DRAFT', 'editor', ?)
                    """, versionId, planId, OffsetDateTime.now());
            execute(connection, """
                    INSERT INTO budget_lines
                      (id, version_id, tenant_id, period_start, account_code, cost_center_code,
                       direction, planned_amount, currency)
                    VALUES (?, ?, 'tenant-allocation', DATE '2026-01-01', '740.01', 'A',
                            'EXPENSE', 1000, 'TRY')
                    """, lineA, versionId);
            execute(connection, """
                    INSERT INTO budget_lines
                      (id, version_id, tenant_id, period_start, account_code, cost_center_code,
                       direction, planned_amount, currency)
                    VALUES (?, ?, 'tenant-allocation', DATE '2026-01-01', '740.01', 'B',
                            'EXPENSE', 1000, 'TRY')
                    """, lineB, versionId);
            execute(connection, """
                    INSERT INTO actual_snapshots (
                      id, tenant_id, company_id, fiscal_year, period_start, journal_card_id,
                      journal_row_id, action_type, action_id, resolution_status, direction,
                      normalized_amount, currency, source_hash, is_cancelled, synced_at
                    ) VALUES (
                      ?, 'tenant-allocation', 35, 2026, DATE '2026-01-01', 1, 2, 121, 3,
                      'HEADER_ONLY', 'EXPENSE', 500, 'TRY', ?, FALSE, ?
                    )
                    """, actualId, "b".repeat(64), OffsetDateTime.now());
            execute(connection, """
                    INSERT INTO actual_allocations
                      (id, tenant_id, actual_snapshot_id, budget_line_id, allocated_amount, created_by, created_at)
                    VALUES (?, 'tenant-allocation', ?, ?, 400, 'editor', ?)
                    """, UUID.randomUUID(), actualId, lineA, OffsetDateTime.now());

            assertThatThrownBy(() -> execute(connection, """
                    INSERT INTO actual_allocations
                      (id, tenant_id, actual_snapshot_id, budget_line_id, allocated_amount, created_by, created_at)
                    VALUES (?, 'tenant-allocation', ?, ?, 200, 'editor', ?)
                    """, UUID.randomUUID(), actualId, lineB, OffsetDateTime.now()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("exceeds the accounting row amount");
            connection.rollback();

            assertThat(queryText(connection, """
                    SELECT source_family FROM source_type_registry
                     WHERE action_type=121 AND registry_version=1
                    """)).isEqualTo("EXPENSE_PLAN");
        }
    }

    @Test
    void runtimeConnectionInitSqlResolvesTheNonPublicSchemaWithoutJdbcUrlHints() throws Exception {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(APP_USER);
        config.setPassword(APP_PASSWORD);
        config.setConnectionInitSql("SET search_path TO " + SCHEMA + ", public");
        try (HikariDataSource dataSource = new HikariDataSource(config);
             Connection connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            setTenant(connection, "tenant-runtime");
            assertThat(queryText(connection, """
                    SELECT source_family FROM source_type_registry
                     WHERE action_type=121 AND registry_version=1
                    """)).isEqualTo("EXPENSE_PLAN");
            assertThat(count(connection, "SELECT COUNT(*) FROM budget_plans")).isZero();
            connection.rollback();
        }
    }

    @Test
    void projectActualTablesAreTenantIsolatedAndHistoryIsAppendOnly() throws Exception {
        UUID bindingId = UUID.randomUUID();
        UUID batchId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        try (Connection connection = appConnection()) {
            connection.setAutoCommit(false);
            setTenant(connection, "tenant-project-a");
            execute(connection, """
                    INSERT INTO budget_project_bindings (
                      id, tenant_id, company_id, platform_project_ref, source_system,
                      external_company_no, external_project_id, created_by, created_at
                    ) VALUES (
                      ?, 'tenant-project-a', 35, 'platform-idc1', 'WORKCUBE',
                      35, 44200, 'sync-user', ?
                    )
                    """, bindingId, OffsetDateTime.now());
            execute(connection, """
                    INSERT INTO actual_sync_batches (
                      id, tenant_id, company_id, project_binding_id, source_system,
                      window_from, window_to, status, started_by, started_at
                    ) VALUES (
                      ?, 'tenant-project-a', 35, ?, 'WORKCUBE',
                      DATE '2026-06-01', DATE '2026-06-30', 'RUNNING', 'sync-user', ?
                    )
                    """, batchId, bindingId, OffsetDateTime.now());
            execute(connection, """
                    INSERT INTO actual_snapshots (
                      id, tenant_id, company_id, fiscal_year, period_start,
                      journal_card_id, journal_row_id, resolution_status, direction,
                      normalized_amount, currency, source_hash, is_cancelled, synced_at,
                      project_binding_id, source_system, source_partition, account_code,
                      debit_credit, cost_treatment, sync_batch_id
                    ) VALUES (
                      ?, 'tenant-project-a', 35, 2026, DATE '2026-06-10',
                      9000, 1, 'HEADER_ONLY', 'EXPENSE',
                      100, 'TRY', ?, FALSE, ?, ?, 'WORKCUBE',
                      'ledger-year:2026', '740.01', 'DEBIT',
                      'INCLUDE_COST', ?
                    )
                    """, snapshotId, "c".repeat(64), OffsetDateTime.now(), bindingId, batchId);
            execute(connection, """
                    INSERT INTO actual_snapshot_versions (
                      id, tenant_id, snapshot_id, version_no, sync_batch_id,
                      recorded_reason, source_hash, normalized_amount, currency,
                      debit_credit, account_code, cost_treatment, resolution_status,
                      is_cancelled, recorded_at
                    ) VALUES (
                      ?, 'tenant-project-a', ?, 1, ?, 'FIRST_SEEN', ?,
                      100, 'TRY', 'DEBIT', '740.01', 'INCLUDE_COST',
                      'HEADER_ONLY', FALSE, ?
                    )
                    """, versionId, snapshotId, batchId, "c".repeat(64), OffsetDateTime.now());
            connection.commit();

            connection.setAutoCommit(false);
            setTenant(connection, "tenant-project-a");
            assertThatThrownBy(() -> execute(connection, """
                    UPDATE actual_snapshot_versions
                       SET normalized_amount=0
                     WHERE id=?
                    """, versionId))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("append-only");
            connection.rollback();
        }

        try (Connection otherTenant = appConnection()) {
            otherTenant.setAutoCommit(false);
            setTenant(otherTenant, "tenant-project-b");
            assertThat(count(otherTenant, "SELECT COUNT(*) FROM budget_project_bindings")).isZero();
            assertThat(count(otherTenant, "SELECT COUNT(*) FROM actual_sync_batches")).isZero();
            assertThat(count(otherTenant, "SELECT COUNT(*) FROM actual_snapshot_versions")).isZero();
            assertThatThrownBy(() -> execute(otherTenant, """
                    INSERT INTO budget_project_bindings (
                      id, tenant_id, company_id, platform_project_ref, source_system,
                      external_company_no, external_project_id, created_by, created_at
                    ) VALUES (
                      ?, 'tenant-project-a', 35, 'cross-tenant', 'WORKCUBE',
                      35, 44201, 'attacker', ?
                    )
                    """, UUID.randomUUID(), OffsetDateTime.now()))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            otherTenant.rollback();
        }
    }

    private static Connection appConnection() throws SQLException {
        String jdbcUrl = POSTGRES.getJdbcUrl();
        String separator = jdbcUrl.contains("?") ? "&" : "?";
        return DriverManager.getConnection(
                jdbcUrl + separator + "currentSchema=" + SCHEMA,
                APP_USER,
                APP_PASSWORD);
    }

    private static void setTenant(Connection connection, String tenant) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
            statement.setString(1, tenant);
            statement.execute();
        }
    }

    private static int count(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static String queryText(Connection connection, String sql) throws SQLException {
        try (var statement = connection.createStatement();
             var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void execute(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                Object value = args[i];
                if (value instanceof UUID uuid) {
                    statement.setObject(i + 1, uuid);
                } else if (value instanceof OffsetDateTime time) {
                    statement.setObject(i + 1, time);
                } else if (value instanceof BigDecimal decimal) {
                    statement.setBigDecimal(i + 1, decimal);
                } else {
                    statement.setObject(i + 1, value);
                }
            }
            statement.executeUpdate();
        }
    }
}

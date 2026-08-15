package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@Tag("integration")
@Execution(ExecutionMode.SAME_THREAD)
class BudgetPlanProviderRepositoryMssqlIntegrationTest {

    @Container
    @SuppressWarnings("resource")
    static final MSSQLServerContainer<?> MSSQL =
            new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest")
                    .acceptLicense();

    private static BudgetPlanProviderRepository repository;

    @BeforeAll
    static void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName(MSSQL.getDriverClassName());
        dataSource.setUrl(MSSQL.getJdbcUrl() + ";encrypt=false;trustServerCertificate=true");
        dataSource.setUsername(MSSQL.getUsername());
        dataSource.setPassword(MSSQL.getPassword());
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        jdbc.execute("EXEC('CREATE SCHEMA [workcube_mikrolink]')");
        // PERIOD_YEAR is deliberately NVARCHAR while OUR_COMPANY_ID is INT:
        // the repository scopes both through TRY_CAST, and this asymmetry
        // proves the predicate is robust to Workcube's mixed column typing.
        jdbc.execute("""
                CREATE TABLE [workcube_mikrolink].[BUDGET] (
                    BUDGET_ID BIGINT PRIMARY KEY,
                    BUDGET_NAME NVARCHAR(200),
                    PERIOD_YEAR NVARCHAR(8),
                    BUDGET_STAGE INT,
                    BRANCH_ID BIGINT,
                    DEPARTMENT_ID BIGINT,
                    WORKGROUP_ID BIGINT,
                    PROJECT_ID BIGINT,
                    OUR_COMPANY_ID INT
                )
                """);
        jdbc.execute("""
                CREATE TABLE [workcube_mikrolink].[BUDGET_PLAN] (
                    BUDGET_PLAN_ID BIGINT PRIMARY KEY,
                    BUDGET_ID BIGINT,
                    IS_SCENARIO INT,
                    BRANCH_ID BIGINT,
                    BUDGET_PLAN_DATE DATETIME
                )
                """);
        jdbc.execute("""
                CREATE TABLE [workcube_mikrolink].[BUDGET_PLAN_ROW] (
                    BUDGET_PLAN_ROW_ID BIGINT PRIMARY KEY,
                    BUDGET_PLAN_ID BIGINT,
                    PLAN_DATE DATETIME,
                    DETAIL NVARCHAR(500),
                    EXP_INC_CENTER_ID BIGINT,
                    BUDGET_ITEM_ID BIGINT,
                    BUDGET_ACCOUNT_CODE NVARCHAR(80),
                    ACTIVITY_TYPE_ID BIGINT,
                    PROJECT_ID BIGINT,
                    WORKGROUP_ID BIGINT,
                    ROW_TOTAL_INCOME DECIMAL(19,4),
                    ROW_TOTAL_EXPENSE DECIMAL(19,4)
                )
                """);

        // Company 35 / 2026 — the target scope: one real budget with two rows,
        // one scenario plan row (scenario classification happens in
        // budget-service; the provider must still surface it faithfully).
        insertBudget(jdbc, 9, "2026 Opex", "2026", 35, 2L, 7L, 44200L);
        insertPlan(jdbc, 40, 9, 0, null, "2026-03-01");
        insertRow(jdbc, 17, 40, "2026-03-15", "Bakım bütçesi", "740.01",
                12L, 44200L, 0, 1500);
        insertRow(jdbc, 18, 40, null, "Gelir kalemi", "600.01",
                null, null, 900, 0);
        insertPlan(jdbc, 41, 9, 1, 3L, "2026-04-01");
        insertRow(jdbc, 19, 41, "2026-04-10", "Senaryo", "770.01",
                null, null, 0, 400);

        // Company 35 / 2025 — outside the requested fiscal year.
        insertBudget(jdbc, 10, "2025 Opex", "2025", 35, null, null, null);
        insertPlan(jdbc, 50, 10, 0, null, "2025-03-01");
        insertRow(jdbc, 30, 50, "2025-03-15", "Eski yıl", "740.01",
                null, null, 0, 100);

        // Company 350 — the prefix-collision decoy company.
        insertBudget(jdbc, 11, "Decoy", "2026", 350, null, null, null);
        insertPlan(jdbc, 60, 11, 0, null, "2026-03-01");
        insertRow(jdbc, 40, 60, "2026-03-15", "Yabancı şirket", "740.01",
                null, null, 0, 999);

        repository = new BudgetPlanProviderRepository(jdbc);
    }

    @Test
    void returnsOnlyTheRequestedCompanyAndFiscalYear() {
        var rows = repository.find(35L, 2026, 0L, 10);

        assertThat(rows).hasSize(3);
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.sourceCompanyId()).isEqualTo(35L);
            assertThat(row.fiscalYear()).isEqualTo(2026);
        });
        assertThat(rows).extracting(r -> r.budgetPlanRowId())
                .containsExactly(17L, 18L, 19L);
    }

    @Test
    void normalizesRowFieldsAndFallsBackToPlanHeaderValues() {
        var rows = repository.find(35L, 2026, 0L, 10);

        var first = rows.getFirst();
        assertThat(first.accountCode()).isEqualTo("740.01");
        assertThat(first.expenseTotal()).isEqualByComparingTo("1500");
        assertThat(first.incomeTotal()).isEqualByComparingTo("0");
        assertThat(first.expIncCenterId()).isEqualTo(12L);
        assertThat(first.projectId()).isEqualTo(44200L);
        assertThat(first.departmentId()).isEqualTo(7L);
        assertThat(first.branchId()).isEqualTo(2L);
        assertThat(first.scenario()).isFalse();
        assertThat(first.planDate()).isEqualTo("2026-03-15");

        // Row 18 has no PLAN_DATE and no row project: plan/header fallbacks apply.
        var second = rows.get(1);
        assertThat(second.planDate()).isEqualTo("2026-03-01");
        assertThat(second.projectId()).isEqualTo(44200L);

        // Row 19 belongs to the scenario plan with a plan-level branch override.
        var third = rows.getLast();
        assertThat(third.scenario()).isTrue();
        assertThat(third.branchId()).isEqualTo(3L);
    }

    @Test
    void keysetPaginationAdvancesWithoutOverlap() {
        var firstPage = repository.find(35L, 2026, 0L, 2);
        assertThat(firstPage).extracting(r -> r.budgetPlanRowId())
                .containsExactly(17L, 18L);

        var secondPage = repository.find(35L, 2026, 18L, 2);
        assertThat(secondPage).extracting(r -> r.budgetPlanRowId())
                .containsExactly(19L);
    }

    private static void insertBudget(
            JdbcTemplate jdbc, long id, String name, String year, int companyId,
            Long branchId, Long departmentId, Long projectId) {
        jdbc.update("""
                INSERT INTO [workcube_mikrolink].[BUDGET]
                  (BUDGET_ID, BUDGET_NAME, PERIOD_YEAR, BUDGET_STAGE,
                   BRANCH_ID, DEPARTMENT_ID, PROJECT_ID, OUR_COMPANY_ID)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?)
                """, id, name, year, branchId, departmentId, projectId, companyId);
    }

    private static void insertPlan(
            JdbcTemplate jdbc, long id, long budgetId, int scenario,
            Long branchId, String planDate) {
        jdbc.update("""
                INSERT INTO [workcube_mikrolink].[BUDGET_PLAN]
                  (BUDGET_PLAN_ID, BUDGET_ID, IS_SCENARIO, BRANCH_ID, BUDGET_PLAN_DATE)
                VALUES (?, ?, ?, ?, ?)
                """, id, budgetId, scenario, branchId, planDate);
    }

    private static void insertRow(
            JdbcTemplate jdbc, long id, long planId, String planDate, String detail,
            String accountCode, Long expIncCenterId, Long projectId,
            int income, int expense) {
        jdbc.update("""
                INSERT INTO [workcube_mikrolink].[BUDGET_PLAN_ROW]
                  (BUDGET_PLAN_ROW_ID, BUDGET_PLAN_ID, PLAN_DATE, DETAIL,
                   BUDGET_ACCOUNT_CODE, EXP_INC_CENTER_ID, PROJECT_ID,
                   ROW_TOTAL_INCOME, ROW_TOTAL_EXPENSE)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, planId, planDate, detail, accountCode,
                expIncCenterId, projectId, income, expense);
    }
}

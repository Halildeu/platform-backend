package com.example.budget.service;

import static com.example.budget.api.WorkcubePlanImportDtos.PlanImportRequest;
import static com.example.budget.api.WorkcubePlanImportDtos.PlanImportResult;
import static com.example.budget.api.WorkcubePlanImportDtos.ProviderBudgetPlanPage;
import static com.example.budget.api.WorkcubePlanImportDtos.ProviderBudgetPlanRow;
import static org.assertj.core.api.Assertions.assertThat;

import com.example.budget.security.BudgetActor;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class WorkcubePlanImportServicePostgresIntegrationTest {
    private static final String SCHEMA = "budget_service";
    private static final String BEARER = "Bearer synthetic-test-token";

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("budget_plan_import")
                    .withUsername("budget_migrator")
                    .withPassword("synthetic-migration-test-password");

    private static HikariDataSource dataSource;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate transactions;
    private static StubPlanProvider provider;
    private static WorkcubePlanImportService service;

    @BeforeAll
    static void setUp() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .table("budget_flyway_history")
                .locations("classpath:db/migration", "classpath:db/vendor/postgresql")
                .load()
                .migrate();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setConnectionInitSql("SET search_path TO " + SCHEMA + ", public");
        dataSource = new HikariDataSource(config);
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        provider = new StubPlanProvider();
        service = new WorkcubePlanImportService(
                jdbc,
                new TenantDatabaseScope(jdbc, true),
                provider,
                new DataSourceTransactionManager(dataSource),
                "TRY");
    }

    @AfterAll
    static void tearDown() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    @BeforeEach
    void resetProvider() {
        provider.rows.set(List.of());
        provider.failure.set(null);
    }

    @Test
    void importCreatesDraftWithMergedSplitAndSkippedRows() {
        BudgetActor actor = actor("tenant-imp1");
        provider.rows.set(List.of(
                // Two rows on the same grain -> merged into one 900 expense line.
                row(1, "740.01", 12L, 44200L, "2026-03-15", 0, 500, false, "Bakım A"),
                row(2, "740.01", 12L, 44200L, "2026-03-20", 0, 400, false, "Bakım B"),
                // Both income and expense -> split into two lines.
                row(3, "600.01", null, null, "2026-04-02", 250, 100, false, "Karma"),
                // Scenario plan -> skipped, never imported.
                row(4, "770.01", null, null, "2026-05-01", 0, 999, true, "Senaryo"),
                // Missing account code -> explicit skip record.
                row(5, "", null, null, "2026-05-01", 0, 50, false, "Kod yok"),
                // Zero amounts -> explicit skip record.
                row(6, "740.02", null, null, "2026-05-01", 0, 0, false, "Sıfır")));

        PlanImportResult result = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        assertThat(result.status()).isEqualTo("COMPLETED");
        assertThat(result.fetchedRows()).isEqualTo(6);
        assertThat(result.importedLines()).isEqualTo(3);
        assertThat(result.mergedRows()).isEqualTo(1);
        assertThat(result.splitRows()).isEqualTo(1);
        assertThat(result.scenarioRows()).isEqualTo(1);
        assertThat(result.skippedRows()).isEqualTo(3);
        assertThat(result.skipSample())
                .extracting(s -> s.reason())
                .containsExactlyInAnyOrder(
                        "SCENARIO_PLAN", "MISSING_ACCOUNT_CODE", "ZERO_AMOUNT");

        List<Map<String, Object>> lines = inTransaction(actor, () -> jdbc.queryForList("""
                SELECT account_code, cost_center_code, project_code, direction,
                       planned_amount, period_start, currency
                  FROM budget_lines WHERE tenant_id=? AND version_id=?
                 ORDER BY account_code, direction
                """, actor.tenantId(), result.versionId()));
        assertThat(lines).hasSize(3);
        assertThat(lines.getFirst())
                .containsEntry("account_code", "600.01")
                .containsEntry("direction", "EXPENSE");
        assertThat(lines.get(1))
                .containsEntry("account_code", "600.01")
                .containsEntry("direction", "INCOME");
        assertThat(lines.getLast())
                .containsEntry("account_code", "740.01")
                .containsEntry("cost_center_code", "wc-expense-center-12")
                .containsEntry("project_code", "wc-project-44200");
        assertThat(((BigDecimal) lines.getLast().get("planned_amount")))
                .isEqualByComparingTo("900");
        assertThat(lines.getLast().get("period_start").toString())
                .isEqualTo("2026-03-01");

        Map<String, Object> version = inTransaction(actor, () -> jdbc.queryForMap("""
                SELECT status, origin, version_no FROM budget_versions
                 WHERE tenant_id=? AND id=?
                """, actor.tenantId(), result.versionId()));
        assertThat(version)
                .containsEntry("status", "DRAFT")
                .containsEntry("origin", "WORKCUBE_IMPORT");
    }

    @Test
    void reimportReusesTheDraftAndReplacesLinesWithoutDuplicates() {
        BudgetActor actor = actor("tenant-imp2");
        provider.rows.set(List.of(
                row(1, "740.01", null, null, "2026-03-15", 0, 500, false, "İlk")));
        PlanImportResult first = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        provider.rows.set(List.of(
                row(1, "740.01", null, null, "2026-03-15", 0, 750, false, "Güncel"),
                row(2, "750.01", null, null, "2026-06-01", 0, 100, false, "Yeni")));
        PlanImportResult second = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        assertThat(second.planId()).isEqualTo(first.planId());
        assertThat(second.versionId()).isEqualTo(first.versionId());
        Integer lineCount = inTransaction(actor, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_lines WHERE tenant_id=? AND version_id=?",
                Integer.class, actor.tenantId(), second.versionId()));
        assertThat(lineCount).isEqualTo(2);
        BigDecimal updated = inTransaction(actor, () -> jdbc.queryForObject("""
                SELECT planned_amount FROM budget_lines
                 WHERE tenant_id=? AND version_id=? AND account_code='740.01'
                """, BigDecimal.class, actor.tenantId(), second.versionId()));
        assertThat(updated).isEqualByComparingTo("750");
    }

    @Test
    void importNeverTouchesASubmittedVersionAndOpensANewDraft() {
        BudgetActor actor = actor("tenant-imp3");
        provider.rows.set(List.of(
                row(1, "740.01", null, null, "2026-03-15", 0, 500, false, "İlk")));
        PlanImportResult first = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        inTransaction(actor, () -> jdbc.update("""
                UPDATE budget_versions
                   SET status='SUBMITTED', submitted_by='planner', submitted_at=NOW()
                 WHERE tenant_id=? AND id=?
                """, actor.tenantId(), first.versionId()));

        provider.rows.set(List.of(
                row(2, "750.01", null, null, "2026-06-01", 0, 100, false, "Yeni")));
        PlanImportResult second = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        assertThat(second.planId()).isEqualTo(first.planId());
        assertThat(second.versionId()).isNotEqualTo(first.versionId());
        Integer submittedLines = inTransaction(actor, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_lines WHERE tenant_id=? AND version_id=?",
                Integer.class, actor.tenantId(), first.versionId()));
        assertThat(submittedLines).isEqualTo(1);
        Map<String, Object> newVersion = inTransaction(actor, () -> jdbc.queryForMap(
                "SELECT status, origin, version_no FROM budget_versions WHERE tenant_id=? AND id=?",
                actor.tenantId(), second.versionId()));
        assertThat(newVersion)
                .containsEntry("status", "DRAFT")
                .containsEntry("origin", "WORKCUBE_IMPORT")
                .containsEntry("version_no", 2);
    }

    @Test
    void tamperedProviderHashBlocksTheBatchAndCreatesNoPlan() {
        BudgetActor actor = actor("tenant-imp4");
        ProviderBudgetPlanRow honest =
                row(1, "740.01", null, null, "2026-03-15", 0, 500, false, "Dürüst");
        ProviderBudgetPlanRow tampered = new ProviderBudgetPlanRow(
                honest.sourceSystem(), honest.sourceCompanyId(), honest.fiscalYear(),
                honest.budgetId(), honest.budgetName(), honest.budgetStage(),
                honest.scenario(), honest.budgetPlanId(), honest.budgetPlanRowId(),
                honest.planDate(), honest.accountCode(), honest.expIncCenterId(),
                honest.budgetItemId(), honest.activityTypeId(), honest.projectId(),
                honest.workgroupId(), honest.departmentId(), honest.branchId(),
                honest.incomeTotal(), new BigDecimal("999999.00"), honest.detail(),
                honest.sourceHash());
        provider.rows.set(List.of(tampered));

        PlanImportResult result = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.failureCode()).isEqualTo("PROVIDER_DATA_INVALID");
        Integer planCount = inTransaction(actor, () -> jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_plans WHERE tenant_id=?",
                Integer.class, actor.tenantId()));
        assertThat(planCount).isZero();
    }

    @Test
    void providerOutageBlocksTheBatchWithAnExplicitCode() {
        BudgetActor actor = actor("tenant-imp5");
        provider.failure.set(HttpClientErrorException.create(
                HttpStatus.FORBIDDEN, "denied", HttpHeaders.EMPTY, new byte[0], null));

        PlanImportResult result = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.failureCode()).isEqualTo("PROVIDER_SCOPE_DENIED");
    }

    @Test
    void existingPlanWithForeignBaseCurrencyBlocksTheImport() {
        BudgetActor actor = actor("tenant-imp6");
        inTransaction(actor, () -> jdbc.update("""
                INSERT INTO budget_plans
                  (id, tenant_id, company_id, fiscal_year, base_currency, created_by, created_at)
                VALUES (?, ?, ?, ?, 'EUR', 'planner', NOW())
                """, UUID.randomUUID(), actor.tenantId(), actor.companyId(), 2026));
        provider.rows.set(List.of(
                row(1, "740.01", null, null, "2026-03-15", 0, 500, false, "TRY satırı")));

        PlanImportResult result = service.importPlans(
                actor, new PlanImportRequest(2026), BEARER);

        assertThat(result.status()).isEqualTo("BLOCKED");
        assertThat(result.failureCode()).isEqualTo("IMPORT_CONFLICT");
    }

    private static BudgetActor actor(String tenant) {
        return new BudgetActor(tenant, 35L, "planner", Set.of(), false);
    }

    private static ProviderBudgetPlanRow row(
            long rowId,
            String accountCode,
            Long expIncCenterId,
            Long projectId,
            String planDate,
            int income,
            int expense,
            boolean scenario,
            String detail) {
        ProviderBudgetPlanRow unhashed = new ProviderBudgetPlanRow(
                "WORKCUBE", 35L, 2026, 9L, "2026 Opex", 1, scenario,
                40L, rowId, planDate == null ? null : LocalDate.parse(planDate),
                accountCode, expIncCenterId, 3L, null, projectId, 5L, 7L, 2L,
                new BigDecimal(income), new BigDecimal(expense), detail, null);
        return new ProviderBudgetPlanRow(
                unhashed.sourceSystem(), unhashed.sourceCompanyId(), unhashed.fiscalYear(),
                unhashed.budgetId(), unhashed.budgetName(), unhashed.budgetStage(),
                unhashed.scenario(), unhashed.budgetPlanId(), unhashed.budgetPlanRowId(),
                unhashed.planDate(), unhashed.accountCode(), unhashed.expIncCenterId(),
                unhashed.budgetItemId(), unhashed.activityTypeId(), unhashed.projectId(),
                unhashed.workgroupId(), unhashed.departmentId(), unhashed.branchId(),
                unhashed.incomeTotal(), unhashed.expenseTotal(), unhashed.detail(),
                WorkcubePlanImportService.providerHash(unhashed));
    }

    private <T> T inTransaction(BudgetActor actor, java.util.function.Supplier<T> work) {
        return transactions.execute(status -> {
            jdbc.execute("SELECT set_config('app.tenant_id', '" + actor.tenantId() + "', true)");
            return work.get();
        });
    }

    static class StubPlanProvider implements BudgetPlanProviderClient {
        final AtomicReference<List<ProviderBudgetPlanRow>> rows =
                new AtomicReference<>(List.of());
        final AtomicReference<RuntimeException> failure = new AtomicReference<>();

        @Override
        public ProviderBudgetPlanPage fetchPlans(
                String authorization,
                long companyId,
                int fiscalYear,
                String cursor,
                int limit) {
            RuntimeException planned = failure.get();
            if (planned != null) {
                throw planned;
            }
            return new ProviderBudgetPlanPage(rows.get(), null, false);
        }
    }
}

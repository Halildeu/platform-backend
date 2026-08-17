package com.example.budget.api;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Wire contracts for the Workcube budget-plan import lane (#3454). */
public final class WorkcubePlanImportDtos {
    private WorkcubePlanImportDtos() {
    }

    /**
     * {@code includeScenarios} is an explicit opt-in: scenario plans
     * (IS_SCENARIO=1) are not approved assignments and are skipped by default;
     * a planner may still pull them into a draft deliberately (e.g. the TEST
     * ERP only carries a scenario demo budget). Null means false.
     */
    public record PlanImportRequest(
            @Min(2000) @Max(2200) int fiscalYear,
            Boolean includeScenarios) {

        public boolean scenariosIncluded() {
            return Boolean.TRUE.equals(includeScenarios);
        }
    }

    /**
     * Client-side mirror of report-service {@code BudgetPlanProviderDtos.BudgetPlanRow}.
     * Field order matches the provider's canonical-hash order; the import service
     * re-computes the hash over these fields and rejects mismatches.
     */
    public record ProviderBudgetPlanRow(
            String sourceSystem,
            long sourceCompanyId,
            int fiscalYear,
            long budgetId,
            String budgetName,
            Integer budgetStage,
            boolean scenario,
            long budgetPlanId,
            long budgetPlanRowId,
            LocalDate planDate,
            String accountCode,
            Long expIncCenterId,
            Long budgetItemId,
            Long activityTypeId,
            Long projectId,
            Long workgroupId,
            Long departmentId,
            Long branchId,
            BigDecimal incomeTotal,
            BigDecimal expenseTotal,
            String detail,
            String sourceHash) {
    }

    public record ProviderBudgetPlanPage(
            List<ProviderBudgetPlanRow> rows,
            String nextCursor,
            boolean hasMore) {
    }

    public record PlanImportSkip(
            long sourceBudgetPlanRowId,
            long sourceBudgetPlanId,
            String reason,
            String detail) {
    }

    public record PlanImportResult(
            UUID batchId,
            UUID planId,
            UUID versionId,
            String status,
            int fetchedRows,
            int importedLines,
            int mergedRows,
            int splitRows,
            int scenarioRows,
            int skippedRows,
            List<PlanImportSkip> skipSample,
            String failureCode,
            OffsetDateTime startedAt,
            OffsetDateTime finishedAt) {
    }
}

package com.example.report.workcube;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire contract of the read-only Workcube budget-plan provider consumed by
 * budget-service (#3454). Field order is load-bearing: the provider hash is a
 * SHA-256 over every field in declaration order (excluding {@code sourceHash}
 * itself) and budget-service re-computes the same canonical string to reject
 * tampered or drifted payloads — keep both sides in lockstep.
 */
public final class BudgetPlanProviderDtos {
    private BudgetPlanProviderDtos() {
    }

    public record BudgetPlanRow(
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

    public record BudgetPlanPage(
            List<BudgetPlanRow> rows,
            String nextCursor,
            boolean hasMore) {
    }
}

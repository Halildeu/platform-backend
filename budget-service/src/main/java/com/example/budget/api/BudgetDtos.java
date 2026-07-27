package com.example.budget.api;

import com.example.budget.domain.VersionStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class BudgetDtos {
    private BudgetDtos() {
    }

    public record CreateBudgetRequest(
            @NotNull @Min(1) Long companyId,
            @Min(2000) @Max(2200) int fiscalYear,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency) {
    }

    public record ReplaceLinesRequest(@NotEmpty @Size(max = 10_000) List<@Valid BudgetLineInput> lines) {
    }

    public record BudgetLineInput(
            @NotBlank @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])") String period,
            @NotBlank @Size(max = 80) String accountCode,
            @Size(max = 80) String costCenterCode,
            @Size(max = 80) String projectCode,
            @Size(max = 80) String departmentCode,
            @Size(max = 80) String branchCode,
            @NotBlank @Pattern(regexp = "EXPENSE|INCOME") String direction,
            @NotNull @DecimalMin("0.00") BigDecimal plannedAmount,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @Size(max = 500) String description) {
    }

    public record BudgetLineView(
            UUID id,
            String period,
            String accountCode,
            String costCenterCode,
            String projectCode,
            String departmentCode,
            String branchCode,
            String direction,
            BigDecimal plannedAmount,
            String currency,
            String description) {
    }

    public record BudgetPlanView(
            UUID planId,
            UUID versionId,
            long companyId,
            int fiscalYear,
            String baseCurrency,
            int versionNo,
            VersionStatus status,
            String submittedBy,
            String approvedBy,
            List<BudgetLineView> lines) {
    }

    public record BudgetControlSummary(
            UUID planId,
            UUID versionId,
            long companyId,
            int fiscalYear,
            String currency,
            VersionStatus versionStatus,
            BigDecimal plan,
            BigDecimal accountingActual,
            BigDecimal allocatedActual,
            BigDecimal unallocatedActual,
            BigDecimal unresolvedActual,
            BigDecimal commitment,
            BigDecimal remaining,
            BigDecimal etc,
            BigDecimal eac,
            BigDecimal variance,
            String forecastStatus,
            String actualDefinition,
            String remainingDefinition) {
    }
}

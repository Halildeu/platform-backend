package com.example.budget.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ProjectActualDtos {
    private ProjectActualDtos() {
    }

    public record CreateProjectBindingRequest(
            @NotBlank @Size(max = 160) String platformProjectRef,
            @NotBlank @Pattern(regexp = "[A-Z0-9_-]{2,60}") String sourceSystem,
            @NotNull @Min(1) Long externalCompanyNo,
            @NotNull @Min(1) Long externalProjectId,
            @Size(max = 80) String externalProjectCode) {
    }

    public record ProjectBindingView(
            UUID id,
            long companyId,
            String platformProjectRef,
            String sourceSystem,
            long externalCompanyNo,
            long externalProjectId,
            String externalProjectCode,
            OffsetDateTime verifiedAt) {
    }

    public record ReplaceCostRulesRequest(
            @NotEmpty @Size(max = 500) List<@Valid CostRuleInput> rules) {
    }

    public record CostRuleInput(
            @Min(1) @Max(10000) int priority,
            @NotBlank @Size(max = 80) String accountPrefix,
            @NotBlank @Pattern(regexp =
                    "INCLUDE_COST|INCLUDE_NEGATIVE_COST|EXCLUDE_COUNTERPART|EXCLUDE_TRANSFER|REQUIRES_REVIEW")
            String debitTreatment,
            @NotBlank @Pattern(regexp =
                    "INCLUDE_COST|INCLUDE_NEGATIVE_COST|EXCLUDE_COUNTERPART|EXCLUDE_TRANSFER|REQUIRES_REVIEW")
            String creditTreatment,
            @Size(max = 40) String documentType) {
    }

    public record CostRuleSetView(
            UUID id,
            long companyId,
            int versionNo,
            String status,
            List<CostRuleInput> rules) {
    }

    public record ProjectActualSyncRequest(
            @NotNull LocalDate from,
            @NotNull LocalDate to) {
    }

    public record ProjectActualRow(
            UUID id,
            LocalDate postingDate,
            String accountCode,
            String debitCredit,
            BigDecimal accountingAmount,
            BigDecimal classifiedCostAmount,
            String currency,
            String costTreatment,
            Integer costRuleVersion,
            String documentType,
            String documentNo,
            String resolutionStatus,
            boolean cancelled,
            long journalCardId,
            long journalRowId,
            Integer actionType,
            Long actionId,
            int sourceLedgerYear,
            OffsetDateTime syncedAt) {
    }

    public record ProjectActualSummary(
            UUID projectBindingId,
            LocalDate from,
            LocalDate to,
            String currency,
            BigDecimal accountingActual,
            BigDecimal classifiedCost,
            BigDecimal excludedAmount,
            BigDecimal requiresReviewAmount,
            long rowCount,
            long snapshotRowCount,
            long requiresReviewCount,
            String reconciliationStatus,
            BigDecimal reconciliationDifference,
            OffsetDateTime lastSyncAt) {
    }

    public record ProjectActualSyncResult(
            UUID batchId,
            String status,
            String failureCode,
            int sourceRowCount,
            int changedRowCount,
            int tombstoneRowCount,
            BigDecimal sourceAmount,
            BigDecimal snapshotAmount,
            BigDecimal differenceAmount,
            String sourceFingerprint,
            OffsetDateTime finishedAt) {
    }

    public record ProviderActualRow(
            String sourceSystem,
            int sourceLedgerYear,
            long sourceCompanyId,
            long sourceProjectId,
            long journalCardId,
            long journalRowId,
            LocalDate postingDate,
            String accountCode,
            String debitCredit,
            BigDecimal signedAmount,
            String currency,
            Integer actionType,
            Long actionId,
            String documentType,
            String documentNo,
            String resolutionStatus,
            boolean cancelled,
            String sourceHash) {
    }

    public record ProviderActualPage(
            List<ProviderActualRow> rows,
            String nextCursor,
            boolean hasMore) {
    }
}

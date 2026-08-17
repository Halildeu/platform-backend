package com.example.budget.service;

import static com.example.budget.api.WorkcubePlanImportDtos.PlanImportRequest;
import static com.example.budget.api.WorkcubePlanImportDtos.PlanImportResult;
import static com.example.budget.api.WorkcubePlanImportDtos.PlanImportSkip;
import static com.example.budget.api.WorkcubePlanImportDtos.ProviderBudgetPlanPage;
import static com.example.budget.api.WorkcubePlanImportDtos.ProviderBudgetPlanRow;

import com.example.budget.security.BudgetActor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

/**
 * Imports existing Workcube budget-plan assignments into a versioned
 * {@code WORKCUBE_IMPORT} draft (#3454). Mirrors the actuals sync shape:
 * TX-1 opens the batch, the provider fetch runs outside any transaction, and
 * TX-2 materializes — so the HTTP call never holds a DB connection. Skipped
 * source rows are persisted per-row with a reason; nothing drops silently.
 * MSSQL stays read-only throughout (the provider is a GET).
 */
@Service
public class WorkcubePlanImportService {
    static final String ORIGIN_WORKCUBE_IMPORT = "WORKCUBE_IMPORT";
    private static final int PROVIDER_PAGE_SIZE = 1000;
    private static final int MAX_PROVIDER_PAGES = 1000;
    private static final int SKIP_SAMPLE_LIMIT = 50;
    private static final int MIN_FISCAL_YEAR = 2000;
    private static final int MAX_FISCAL_YEAR = 2200;

    private final JdbcTemplate jdbc;
    private final TenantDatabaseScope tenantScope;
    private final BudgetPlanProviderClient provider;
    private final TransactionTemplate transactions;
    private final String defaultBaseCurrency;

    public WorkcubePlanImportService(
            JdbcTemplate jdbc,
            TenantDatabaseScope tenantScope,
            BudgetPlanProviderClient provider,
            PlatformTransactionManager transactionManager,
            @Value("${budget.import.default-base-currency:TRY}") String defaultBaseCurrency) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
        this.defaultBaseCurrency = defaultBaseCurrency;
    }

    public PlanImportResult importPlans(
            BudgetActor actor, PlanImportRequest request, String authorization) {
        if (request.fiscalYear() < MIN_FISCAL_YEAR || request.fiscalYear() > MAX_FISCAL_YEAR) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Fiscal year is outside the supported range");
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Bearer token is required for read-only source access");
        }
        ImportStart start = transactions.execute(status -> startBatch(actor, request));
        if (start == null) {
            throw new IllegalStateException("Failed to start budget-plan import transaction");
        }

        List<ProviderBudgetPlanRow> sourceRows;
        try {
            sourceRows = fetchAll(authorization, actor.companyId(), request.fiscalYear());
        } catch (HttpClientErrorException.Unauthorized rejected) {
            return blockBatchInNewTransaction(actor, start, "PROVIDER_TOKEN_REJECTED");
        } catch (HttpClientErrorException.Forbidden denied) {
            return blockBatchInNewTransaction(actor, start, "PROVIDER_SCOPE_DENIED");
        } catch (RestClientException unavailable) {
            return blockBatchInNewTransaction(actor, start, "PROVIDER_UNAVAILABLE");
        }

        try {
            PlanImportResult result = transactions.execute(status -> {
                tenantScope.apply(actor.tenantId());
                return materialize(actor, start, request, sourceRows);
            });
            if (result == null) {
                throw new IllegalStateException("Budget-plan import transaction returned no result");
            }
            return result;
        } catch (ResponseStatusException invalid) {
            return blockBatchInNewTransaction(actor, start, failureCode(invalid));
        } catch (RuntimeException writeFailure) {
            blockBatchInNewTransaction(actor, start, "IMPORT_WRITE_FAILED");
            throw writeFailure;
        }
    }

    private ImportStart startBatch(BudgetActor actor, PlanImportRequest request) {
        tenantScope.apply(actor.tenantId());
        UUID batchId = UUID.randomUUID();
        OffsetDateTime startedAt = now();
        jdbc.update("""
                INSERT INTO budget_plan_import_batches (
                  id, tenant_id, company_id, fiscal_year, source_system,
                  status, started_by, started_at
                ) VALUES (?, ?, ?, ?, 'WORKCUBE', 'RUNNING', ?, ?)
                """, batchId, actor.tenantId(), actor.companyId(), request.fiscalYear(),
                actor.subject(), startedAt);
        return new ImportStart(batchId, startedAt);
    }

    private List<ProviderBudgetPlanRow> fetchAll(
            String authorization, long companyId, int fiscalYear) {
        List<ProviderBudgetPlanRow> rows = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < MAX_PROVIDER_PAGES; page++) {
            ProviderBudgetPlanPage response = provider.fetchPlans(
                    authorization, companyId, fiscalYear, cursor, PROVIDER_PAGE_SIZE);
            if (response == null || response.rows() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Budget-plan provider returned an empty page");
            }
            rows.addAll(response.rows());
            if (!response.hasMore()) {
                return rows;
            }
            String next = response.nextCursor();
            if (next == null || next.isBlank() || next.equals(cursor)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Budget-plan provider cursor did not advance");
            }
            cursor = next;
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Budget-plan provider exceeded the page budget");
    }

    private PlanImportResult materialize(
            BudgetActor actor,
            ImportStart start,
            PlanImportRequest request,
            List<ProviderBudgetPlanRow> sourceRows) {
        Set<Long> seenRowIds = new HashSet<>();
        for (ProviderBudgetPlanRow row : sourceRows) {
            validateSourceRow(actor, request, row);
            if (!seenRowIds.add(row.budgetPlanRowId())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Budget-plan provider returned duplicate row " + row.budgetPlanRowId());
            }
        }

        List<PlanImportSkip> skips = new ArrayList<>();
        Map<LineGrain, BigDecimal> lines = new LinkedHashMap<>();
        Map<LineGrain, String> descriptions = new HashMap<>();
        int scenarioRows = 0;
        int splitRows = 0;
        int mergedRows = 0;

        for (ProviderBudgetPlanRow row : sourceRows) {
            if (row.scenario()) {
                // scenarioRows counts every scenario row encountered; whether it
                // becomes a skip record or a draft line is the planner's explicit
                // includeScenarios choice (default: skip — a scenario is not an
                // approved assignment).
                scenarioRows++;
                if (!request.scenariosIncluded()) {
                    skips.add(skip(row, "SCENARIO_PLAN", row.budgetName()));
                    continue;
                }
            }
            String skipReason = classifySkip(row);
            if (skipReason != null) {
                skips.add(skip(row, skipReason, row.detail()));
                continue;
            }
            boolean income = row.incomeTotal().signum() > 0;
            boolean expense = row.expenseTotal().signum() > 0;
            if (income && expense) {
                splitRows++;
            }
            if (expense) {
                mergedRows += accumulate(
                        lines, descriptions, row, request.fiscalYear(), "EXPENSE", row.expenseTotal());
            }
            if (income) {
                mergedRows += accumulate(
                        lines, descriptions, row, request.fiscalYear(), "INCOME", row.incomeTotal());
            }
        }

        PlanTarget target = resolveTarget(actor, request.fiscalYear(), start.batchId());
        jdbc.update("DELETE FROM budget_lines WHERE tenant_id=? AND version_id=?",
                actor.tenantId(), target.versionId());
        for (Map.Entry<LineGrain, BigDecimal> entry : lines.entrySet()) {
            LineGrain grain = entry.getKey();
            jdbc.update("""
                    INSERT INTO budget_lines (
                      id, version_id, tenant_id, period_start, account_code, cost_center_code,
                      project_code, department_code, branch_code, direction, planned_amount,
                      currency, description
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), target.versionId(), actor.tenantId(),
                    Date.valueOf(grain.periodStart()), grain.accountCode(),
                    grain.costCenterCode(), grain.projectCode(), grain.departmentCode(),
                    grain.branchCode(), grain.direction(), entry.getValue(),
                    target.baseCurrency(), descriptions.get(grain));
        }
        for (PlanImportSkip skip : skips) {
            jdbc.update("""
                    INSERT INTO budget_plan_import_skips (
                      id, batch_id, tenant_id, source_budget_plan_row_id,
                      source_budget_plan_id, reason, detail
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), start.batchId(), actor.tenantId(),
                    skip.sourceBudgetPlanRowId(), skip.sourceBudgetPlanId(),
                    skip.reason(), skip.detail());
        }

        OffsetDateTime finishedAt = now();
        jdbc.update("""
                UPDATE budget_plan_import_batches
                   SET status='COMPLETED', plan_id=?, version_id=?, fetched_rows=?,
                       imported_lines=?, merged_rows=?, split_rows=?, scenario_rows=?,
                       skipped_rows=?, source_fingerprint=?, finished_at=?
                 WHERE id=? AND tenant_id=?
                """, target.planId(), target.versionId(), sourceRows.size(),
                lines.size(), mergedRows, splitRows, scenarioRows, skips.size(),
                fingerprint(sourceRows), finishedAt, start.batchId(), actor.tenantId());
        audit(actor, "BUDGET_VERSION", target.versionId(), "BUDGET_PLAN_IMPORTED",
                "batch=" + start.batchId()
                        + " fetched=" + sourceRows.size()
                        + " lines=" + lines.size()
                        + " skipped=" + skips.size());

        return new PlanImportResult(
                start.batchId(), target.planId(), target.versionId(), "COMPLETED",
                sourceRows.size(), lines.size(), mergedRows, splitRows, scenarioRows,
                skips.size(), skips.subList(0, Math.min(skips.size(), SKIP_SAMPLE_LIMIT)),
                null, start.startedAt(), finishedAt);
    }

    private void validateSourceRow(
            BudgetActor actor, PlanImportRequest request, ProviderBudgetPlanRow row) {
        if (!"WORKCUBE".equals(row.sourceSystem())
                || row.sourceCompanyId() != actor.companyId()
                || row.fiscalYear() != request.fiscalYear()
                || row.budgetPlanRowId() < 1
                || row.budgetPlanId() < 1
                || row.incomeTotal() == null
                || row.expenseTotal() == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Budget-plan provider row failed integrity checks");
        }
        if (row.sourceHash() == null || !row.sourceHash().equals(providerHash(row))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Budget-plan provider row hash mismatch");
        }
    }

    private String classifySkip(ProviderBudgetPlanRow row) {
        if (row.accountCode() == null || row.accountCode().isBlank()) {
            return "MISSING_ACCOUNT_CODE";
        }
        // No period-based skips: the line period is derived from the budget's
        // fiscal year (annual bucket), never from PLAN_DATE — see accumulate().
        // MISSING_PERIOD / PERIOD_OUTSIDE_FISCAL_YEAR stay in the V5/V6 CHECK
        // as historical enum values only.
        if (row.incomeTotal().signum() < 0 || row.expenseTotal().signum() < 0) {
            return "NEGATIVE_AMOUNT";
        }
        if (row.incomeTotal().signum() == 0 && row.expenseTotal().signum() == 0) {
            return "ZERO_AMOUNT";
        }
        return null;
    }

    private int accumulate(
            Map<LineGrain, BigDecimal> lines,
            Map<LineGrain, String> descriptions,
            ProviderBudgetPlanRow row,
            int fiscalYear,
            String direction,
            BigDecimal amount) {
        // Annual bucket, deliberately: BUDGET_PLAN_ROW.PLAN_DATE is the ENTRY
        // date, not the budget period — measured live on gitops#3474 (the 2026
        // demo budget's rows cluster on 2025-06 and 2025-12, i.e. two planning
        // sessions during 2025). The source carries no month signal for the
        // plan period, so inventing one from the entry date would be fake
        // granularity; the honest period is the budget's fiscal year itself.
        LineGrain grain = new LineGrain(
                LocalDate.of(fiscalYear, 1, 1),
                row.accountCode().trim(),
                canonicalCode("wc-expense-center-", row.expIncCenterId()),
                canonicalCode("wc-project-", row.projectId()),
                canonicalCode("wc-department-", row.departmentId()),
                canonicalCode("wc-branch-", row.branchId()),
                direction);
        BigDecimal previous = lines.putIfAbsent(grain, amount);
        if (previous == null) {
            descriptions.put(grain, description(row));
            return 0;
        }
        lines.put(grain, previous.add(amount));
        return 1;
    }

    private static String canonicalCode(String prefix, Long externalId) {
        return externalId == null ? null : prefix + externalId;
    }

    private static String description(ProviderBudgetPlanRow row) {
        String base = row.detail() == null || row.detail().isBlank()
                ? row.budgetName()
                : row.detail().trim();
        String suffix = " [workcube-import row " + row.budgetPlanRowId() + "]";
        if (base == null || base.isBlank()) {
            return suffix.trim();
        }
        int maxBase = 500 - suffix.length();
        String trimmed = base.length() > maxBase ? base.substring(0, maxBase) : base;
        return trimmed + suffix;
    }

    private PlanTarget resolveTarget(BudgetActor actor, int fiscalYear, UUID batchId) {
        List<PlanTarget> existingPlan = jdbc.query("""
                SELECT id, base_currency FROM budget_plans
                 WHERE tenant_id=? AND company_id=? AND fiscal_year=?
                """, (rs, rowNum) -> new PlanTarget(
                        rs.getObject("id", UUID.class), null, rs.getString("base_currency")),
                actor.tenantId(), actor.companyId(), fiscalYear);
        UUID planId;
        String baseCurrency;
        OffsetDateTime now = now();
        if (existingPlan.isEmpty()) {
            planId = UUID.randomUUID();
            baseCurrency = defaultBaseCurrency;
            jdbc.update("""
                    INSERT INTO budget_plans
                      (id, tenant_id, company_id, fiscal_year, base_currency, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, planId, actor.tenantId(), actor.companyId(), fiscalYear,
                    baseCurrency, actor.subject(), now);
        } else {
            planId = existingPlan.getFirst().planId();
            baseCurrency = existingPlan.getFirst().baseCurrency();
            if (!defaultBaseCurrency.equals(baseCurrency)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Existing plan base currency differs from the Workcube import currency");
            }
        }

        List<UUID> draft = jdbc.query("""
                SELECT id FROM budget_versions
                 WHERE tenant_id=? AND plan_id=? AND origin=? AND status='DRAFT'
                 ORDER BY version_no DESC
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                actor.tenantId(), planId, ORIGIN_WORKCUBE_IMPORT);
        if (!draft.isEmpty()) {
            UUID versionId = draft.getFirst();
            jdbc.update("UPDATE budget_versions SET origin_batch_id=? WHERE id=? AND tenant_id=?",
                    batchId, versionId, actor.tenantId());
            return new PlanTarget(planId, versionId, baseCurrency);
        }
        UUID versionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO budget_versions
                  (id, plan_id, tenant_id, version_no, status, created_by, created_at,
                   origin, origin_batch_id)
                SELECT ?, ?, ?, COALESCE(MAX(version_no), 0) + 1, 'DRAFT', ?, ?, ?, ?
                  FROM budget_versions WHERE tenant_id=? AND plan_id=?
                """, versionId, planId, actor.tenantId(), actor.subject(), now,
                ORIGIN_WORKCUBE_IMPORT, batchId, actor.tenantId(), planId);
        return new PlanTarget(planId, versionId, baseCurrency);
    }

    private PlanImportResult blockBatchInNewTransaction(
            BudgetActor actor, ImportStart start, String failureCode) {
        PlanImportResult result = transactions.execute(status -> {
            tenantScope.apply(actor.tenantId());
            OffsetDateTime finishedAt = now();
            jdbc.update("""
                    UPDATE budget_plan_import_batches
                       SET status='BLOCKED', failure_code=?, finished_at=?
                     WHERE id=? AND tenant_id=?
                    """, failureCode, finishedAt, start.batchId(), actor.tenantId());
            audit(actor, "PLAN_IMPORT_BATCH", start.batchId(),
                    "BUDGET_PLAN_IMPORT_BLOCKED", failureCode);
            return new PlanImportResult(
                    start.batchId(), null, null, "BLOCKED",
                    0, 0, 0, 0, 0, 0, List.of(), failureCode,
                    start.startedAt(), finishedAt);
        });
        if (result == null) {
            throw new IllegalStateException("Failed to block budget-plan import batch");
        }
        return result;
    }

    private static String failureCode(ResponseStatusException failure) {
        if (failure.getStatusCode() == HttpStatus.BAD_GATEWAY) {
            return "PROVIDER_DATA_INVALID";
        }
        if (failure.getStatusCode() == HttpStatus.CONFLICT) {
            return "IMPORT_CONFLICT";
        }
        return "IMPORT_VALIDATION_FAILED";
    }

    /**
     * Byte-identical re-implementation of report-service
     * {@code BudgetPlanProviderService.canonical(...)} — pinned by
     * {@code WorkcubePlanImportHashContractTest} on both sides.
     */
    static String providerHash(ProviderBudgetPlanRow row) {
        String canonical = String.join("|",
                row.sourceSystem(),
                Long.toString(row.sourceCompanyId()),
                Integer.toString(row.fiscalYear()),
                Long.toString(row.budgetId()),
                String.valueOf(row.budgetName()),
                String.valueOf(row.budgetStage()),
                Boolean.toString(row.scenario()),
                Long.toString(row.budgetPlanId()),
                Long.toString(row.budgetPlanRowId()),
                String.valueOf(row.planDate()),
                String.valueOf(row.accountCode()),
                String.valueOf(row.expIncCenterId()),
                String.valueOf(row.budgetItemId()),
                String.valueOf(row.activityTypeId()),
                String.valueOf(row.projectId()),
                String.valueOf(row.workgroupId()),
                String.valueOf(row.departmentId()),
                String.valueOf(row.branchId()),
                row.incomeTotal().toPlainString(),
                row.expenseTotal().toPlainString(),
                String.valueOf(row.detail()));
        return sha256(canonical);
    }

    private static String fingerprint(List<ProviderBudgetPlanRow> rows) {
        String joined = rows.stream()
                .sorted(java.util.Comparator.comparingLong(ProviderBudgetPlanRow::budgetPlanRowId))
                .map(row -> row.budgetPlanRowId() + ":" + row.sourceHash())
                .reduce(new StringBuilder(),
                        (sb, item) -> sb.append(item).append('\n'),
                        StringBuilder::append)
                .toString();
        return sha256(joined);
    }

    private static PlanImportSkip skip(
            ProviderBudgetPlanRow row, String reason, String detail) {
        String bounded = detail == null ? null
                : detail.length() > 500 ? detail.substring(0, 500) : detail;
        return new PlanImportSkip(
                row.budgetPlanRowId(), row.budgetPlanId(), reason, bounded);
    }

    private void audit(
            BudgetActor actor,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload) {
        jdbc.update("""
                INSERT INTO budget_audit_events
                  (id, tenant_id, aggregate_type, aggregate_id, event_type, actor_id, payload_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), aggregateType, aggregateId,
                eventType, actor.subject(), sha256(payload), now());
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record ImportStart(UUID batchId, OffsetDateTime startedAt) {
    }

    private record PlanTarget(UUID planId, UUID versionId, String baseCurrency) {
    }

    record LineGrain(
            LocalDate periodStart,
            String accountCode,
            String costCenterCode,
            String projectCode,
            String departmentCode,
            String branchCode,
            String direction) {

        LineGrain {
            Objects.requireNonNull(periodStart);
            Objects.requireNonNull(accountCode);
            Objects.requireNonNull(direction);
        }
    }
}

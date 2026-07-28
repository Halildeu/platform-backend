package com.example.budget.service;

import static com.example.budget.api.ProjectActualDtos.*;

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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProjectActualService {
    private static final int PROVIDER_PAGE_SIZE = 1000;
    private static final int MAX_PROVIDER_PAGES = 1000;
    private static final BigDecimal RECONCILIATION_TOLERANCE = new BigDecimal("0.01");

    private final JdbcTemplate jdbc;
    private final TenantDatabaseScope tenantScope;
    private final ProjectActualProviderClient provider;
    private final TransactionTemplate transactions;

    public ProjectActualService(
            JdbcTemplate jdbc,
            TenantDatabaseScope tenantScope,
            ProjectActualProviderClient provider,
            PlatformTransactionManager transactionManager) {
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
        this.provider = provider;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional
    public ProjectBindingView createBinding(
            BudgetActor actor,
            CreateProjectBindingRequest request) {
        tenantScope.apply(actor.tenantId());
        requireProjectAccess(actor, request.externalProjectId());
        if (request.externalCompanyNo() != actor.companyId()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "External company must match the authorized company scope");
        }
        List<ProjectBindingView> existing = jdbc.query("""
                SELECT id, company_id, platform_project_ref, source_system,
                       external_company_no, external_project_id, external_project_code, verified_at
                  FROM budget_project_bindings
                 WHERE tenant_id=? AND company_id=? AND source_system=?
                   AND external_company_no=? AND external_project_id=?
                """, (rs, rowNum) -> bindingView(rs),
                actor.tenantId(), actor.companyId(), request.sourceSystem(),
                request.externalCompanyNo(), request.externalProjectId());
        if (!existing.isEmpty()) {
            ProjectBindingView binding = existing.getFirst();
            if (!binding.platformProjectRef().equals(request.platformProjectRef())) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "External project is already bound to a different platform project reference");
            }
            return binding;
        }

        UUID id = UUID.randomUUID();
        OffsetDateTime now = now();
        try {
            jdbc.update("""
                    INSERT INTO budget_project_bindings (
                      id, tenant_id, company_id, platform_project_ref, source_system,
                      external_company_no, external_project_id, external_project_code,
                      created_by, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, actor.tenantId(), actor.companyId(),
                    request.platformProjectRef().trim(), request.sourceSystem().trim(),
                    request.externalCompanyNo(), request.externalProjectId(),
                    trimToNull(request.externalProjectCode()), actor.subject(), now);
        } catch (DataIntegrityViolationException conflict) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Project binding already exists", conflict);
        }
        audit(actor, "PROJECT_BINDING", id, "PROJECT_BINDING_CREATED",
                request.sourceSystem() + ":" + request.externalProjectId());
        return requireBinding(actor, id).view();
    }

    @Transactional(readOnly = true)
    public ProjectBindingView findBinding(
            BudgetActor actor,
            String requestedSourceSystem,
            long externalProjectId) {
        tenantScope.apply(actor.tenantId());
        requireProjectAccess(actor, externalProjectId);
        String sourceSystem = requestedSourceSystem == null
                ? ""
                : requestedSourceSystem.trim().toUpperCase(java.util.Locale.ROOT);
        if (externalProjectId < 1 || !sourceSystem.matches("[A-Z0-9_-]{2,60}")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Source system and external project are invalid");
        }
        List<ProjectBindingView> bindings = jdbc.query("""
                SELECT id, company_id, platform_project_ref, source_system,
                       external_company_no, external_project_id, external_project_code, verified_at
                  FROM budget_project_bindings
                 WHERE tenant_id=? AND company_id=? AND source_system=?
                   AND external_company_no=? AND external_project_id=?
                """, (rs, rowNum) -> bindingView(rs),
                actor.tenantId(), actor.companyId(), sourceSystem,
                actor.companyId(), externalProjectId);
        if (bindings.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Project actuals binding was not found");
        }
        return bindings.getFirst();
    }

    @Transactional
    public CostRuleSetView replaceAndActivateRules(
            BudgetActor actor,
            ReplaceCostRulesRequest request) {
        tenantScope.apply(actor.tenantId());
        Integer nextVersion = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1
                  FROM cost_rule_sets
                 WHERE tenant_id=? AND company_id=?
                """, Integer.class, actor.tenantId(), actor.companyId());
        int version = nextVersion == null ? 1 : nextVersion;
        UUID ruleSetId = UUID.randomUUID();
        OffsetDateTime now = now();

        jdbc.update("""
                UPDATE cost_rule_sets
                   SET status='RETIRED'
                 WHERE tenant_id=? AND company_id=? AND status='ACTIVE'
                """, actor.tenantId(), actor.companyId());
        jdbc.update("""
                INSERT INTO cost_rule_sets (
                  id, tenant_id, company_id, version_no, status, created_by, created_at,
                  activated_by, activated_at
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?)
                """, ruleSetId, actor.tenantId(), actor.companyId(), version,
                actor.subject(), now, actor.subject(), now);
        for (CostRuleInput rule : request.rules()) {
            jdbc.update("""
                    INSERT INTO cost_account_rules (
                      id, tenant_id, rule_set_id, priority, account_prefix,
                      debit_treatment, credit_treatment, document_type, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actor.tenantId(), ruleSetId, rule.priority(),
                    rule.accountPrefix().trim(), rule.debitTreatment(), rule.creditTreatment(),
                    upperOrNull(rule.documentType()), now);
        }
        audit(actor, "COST_RULE_SET", ruleSetId, "COST_RULE_SET_ACTIVATED",
                "version=" + version + ";rules=" + request.rules().size());
        return new CostRuleSetView(
                ruleSetId, actor.companyId(), version, "ACTIVE", request.rules());
    }

    public ProjectActualSyncResult sync(
            BudgetActor actor,
            UUID bindingId,
            ProjectActualSyncRequest request,
            String authorization) {
        validateWindow(request.from(), request.to());
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Bearer token is required for read-only source access");
        }
        SyncStart start = transactions.execute(status -> startBatch(actor, bindingId, request));
        if (start == null) {
            throw new IllegalStateException("Failed to start project actual sync transaction");
        }

        List<ProviderActualRow> sourceRows;
        try {
            sourceRows = fetchAll(
                    authorization, actor.companyId(), start.binding(), request.from(), request.to());
        } catch (HttpClientErrorException.Unauthorized rejected) {
            return blockBatchInNewTransaction(
                    actor, start, "PROVIDER_TOKEN_REJECTED");
        } catch (HttpClientErrorException.Forbidden denied) {
            return blockBatchInNewTransaction(
                    actor, start, "PROVIDER_SCOPE_DENIED");
        } catch (RestClientException unavailable) {
            return blockBatchInNewTransaction(
                    actor, start, "PROVIDER_UNAVAILABLE");
        }

        try {
            ProjectActualSyncResult result = transactions.execute(status -> {
                tenantScope.apply(actor.tenantId());
                return materialize(actor, start, request, sourceRows);
            });
            if (result == null) {
                throw new IllegalStateException("Project actual sync transaction returned no result");
            }
            return result;
        } catch (ResponseStatusException invalidProviderData) {
            return blockBatchInNewTransaction(
                    actor, start, failureCode(invalidProviderData));
        } catch (RuntimeException writeFailure) {
            blockBatchInNewTransaction(actor, start, "SNAPSHOT_WRITE_FAILED");
            throw writeFailure;
        }
    }

    private SyncStart startBatch(
            BudgetActor actor,
            UUID bindingId,
            ProjectActualSyncRequest request) {
        tenantScope.apply(actor.tenantId());
        Binding binding = requireBinding(actor, bindingId);
        requireProjectAccess(actor, binding.externalProjectId());
        if (!"WORKCUBE".equals(binding.sourceSystem())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "No actuals provider is registered for this source system");
        }
        UUID batchId = UUID.randomUUID();
        OffsetDateTime startedAt = now();
        jdbc.update("""
                INSERT INTO actual_sync_batches (
                  id, tenant_id, company_id, project_binding_id, source_system,
                  window_from, window_to, status, started_by, started_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)
                """, batchId, actor.tenantId(), actor.companyId(), binding.id(),
                binding.sourceSystem(), Date.valueOf(request.from()), Date.valueOf(request.to()),
                actor.subject(), startedAt);
        return new SyncStart(batchId, startedAt, binding);
    }

    private ProjectActualSyncResult materialize(
            BudgetActor actor,
            SyncStart start,
            ProjectActualSyncRequest request,
            List<ProviderActualRow> sourceRows) {
        Binding binding = start.binding();
        UUID batchId = start.batchId();
        Set<String> reportingCurrencies = sourceRows.stream()
                .map(ProviderActualRow::currency)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        if (reportingCurrencies.size() > 1 || reportingCurrencies.contains("XXX")) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Provider rows do not have one verified reporting currency");
        }
        List<Rule> rules = activeRules(actor);
        Set<String> seenKeys = new HashSet<>();
        BigDecimal sourceAmount = BigDecimal.ZERO;
        int changed = 0;
        for (ProviderActualRow row : sourceRows) {
            validateSourceRow(binding, request, row);
            String key = sourceKey(sourcePartition(row), row.journalRowId());
            if (!seenKeys.add(key)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Provider returned a duplicate source row");
            }
            if (!row.cancelled()) {
                sourceAmount = sourceAmount.add(row.signedAmount());
            }
            changed += upsertActual(actor, binding, batchId, row, rules);
        }

        int tombstones = tombstoneMissing(
                actor, binding, batchId, request.from(), request.to(), seenKeys);
        BigDecimal snapshotAmount = amount("""
                SELECT COALESCE(SUM(normalized_amount),0)
                  FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND period_start BETWEEN ? AND ?
                   AND is_cancelled=FALSE
                """, actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(request.from()), Date.valueOf(request.to()));
        long snapshotCount = count("""
                SELECT COUNT(*)
                  FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND period_start BETWEEN ? AND ?
                   AND is_cancelled=FALSE
                """, actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(request.from()), Date.valueOf(request.to()));
        BigDecimal difference = sourceAmount.subtract(snapshotAmount);
        String status = difference.abs().compareTo(RECONCILIATION_TOLERANCE) <= 0
                && snapshotCount == sourceRows.stream().filter(row -> !row.cancelled()).count()
                ? "MATCHED"
                : "DIFFERENCE";
        String fingerprint = fingerprint(sourceRows);
        OffsetDateTime finishedAt = now();

        jdbc.update("""
                UPDATE actual_sync_batches
                   SET status=?, source_row_count=?, changed_row_count=?,
                       tombstone_row_count=?, source_amount=?, snapshot_amount=?,
                       source_fingerprint=?, finished_at=?
                 WHERE id=? AND tenant_id=?
                """, status, sourceRows.size(), changed, tombstones,
                sourceAmount, snapshotAmount, fingerprint, finishedAt,
                batchId, actor.tenantId());
        jdbc.update("""
                INSERT INTO actual_sync_checkpoints (
                  id, tenant_id, company_id, source_system, source_scope_key,
                  window_from, window_to, source_fingerprint, last_batch_id, last_success_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (
                  tenant_id, company_id, source_system, source_scope_key, window_from, window_to
                ) DO UPDATE SET
                  source_fingerprint=EXCLUDED.source_fingerprint,
                  last_batch_id=EXCLUDED.last_batch_id,
                  last_success_at=EXCLUDED.last_success_at
                """, UUID.randomUUID(), actor.tenantId(), actor.companyId(),
                binding.sourceSystem(), "project:" + binding.externalProjectId(),
                Date.valueOf(request.from()), Date.valueOf(request.to()), fingerprint,
                batchId, finishedAt);
        jdbc.update("""
                INSERT INTO budget_reconciliation_runs (
                  id, tenant_id, company_id, fiscal_year, period_start,
                  source_amount, snapshot_amount, difference_amount, status, executed_at,
                  project_binding_id, sync_batch_id, window_from, window_to,
                  source_row_count, snapshot_row_count
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), actor.companyId(),
                request.from().getYear(), Date.valueOf(request.from().withDayOfMonth(1)),
                sourceAmount, snapshotAmount, difference, status, finishedAt,
                binding.id(), batchId, Date.valueOf(request.from()), Date.valueOf(request.to()),
                sourceRows.stream().filter(row -> !row.cancelled()).count(), snapshotCount);
        jdbc.update("""
                UPDATE budget_project_bindings
                   SET verified_at=?
                 WHERE id=? AND tenant_id=? AND company_id=?
                """, finishedAt, binding.id(), actor.tenantId(), actor.companyId());
        audit(actor, "ACTUAL_SYNC_BATCH", batchId, "PROJECT_ACTUALS_SYNCED",
                "status=" + status + ";changed=" + changed + ";tombstones=" + tombstones);
        return new ProjectActualSyncResult(
                batchId, status, null, sourceRows.size(), changed, tombstones,
                sourceAmount, snapshotAmount, difference, fingerprint, finishedAt);
    }

    private ProjectActualSyncResult blockBatchInNewTransaction(
            BudgetActor actor,
            SyncStart start,
            String failureCode) {
        ProjectActualSyncResult result = transactions.execute(status ->
                blockBatch(actor, start, failureCode));
        if (result == null) {
            throw new IllegalStateException("Failed to persist blocked sync batch");
        }
        return result;
    }

    private ProjectActualSyncResult blockBatch(
            BudgetActor actor,
            SyncStart start,
            String failureCode) {
        tenantScope.apply(actor.tenantId());
        OffsetDateTime finishedAt = now();
        jdbc.update("""
                UPDATE actual_sync_batches
                   SET status='BLOCKED', failure_code=?, finished_at=?
                 WHERE id=? AND tenant_id=?
                """, failureCode, finishedAt, start.batchId(), actor.tenantId());
        audit(actor, "ACTUAL_SYNC_BATCH", start.batchId(), "PROJECT_ACTUALS_SYNC_BLOCKED",
                "failure=" + failureCode + ";started=" + start.startedAt());
        return new ProjectActualSyncResult(
                start.batchId(), "BLOCKED", failureCode, 0, 0, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, finishedAt);
    }

    private String failureCode(ResponseStatusException failure) {
        if (failure.getStatusCode() == HttpStatus.CONFLICT) {
            return "SOURCE_GRAIN_CONFLICT";
        }
        if (failure.getStatusCode() == HttpStatus.BAD_GATEWAY) {
            return "PROVIDER_DATA_INVALID";
        }
        return "SYNC_VALIDATION_FAILED";
    }

    @Transactional(readOnly = true)
    public List<ProjectActualRow> rows(
            BudgetActor actor,
            UUID bindingId,
            LocalDate from,
            LocalDate to,
            int requestedLimit) {
        tenantScope.apply(actor.tenantId());
        validateWindow(from, to);
        Binding binding = requireBinding(actor, bindingId);
        requireProjectAccess(actor, binding.externalProjectId());
        int limit = Math.min(Math.max(requestedLimit, 1), 2000);
        return jdbc.query("""
                SELECT id, period_start, account_code, debit_credit, normalized_amount,
                       currency, cost_treatment, cost_rule_version, document_type, document_no,
                       resolution_status, is_cancelled, journal_card_id, journal_row_id,
                       action_type, action_id, source_partition, synced_at
                  FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND period_start BETWEEN ? AND ?
                 ORDER BY period_start DESC, source_partition, journal_row_id
                 LIMIT ?
                """, (rs, rowNum) -> {
                    BigDecimal accountingAmount = rs.getBigDecimal("normalized_amount");
                    String treatment = rs.getString("cost_treatment");
                    BigDecimal classified = isIncluded(treatment)
                            ? accountingAmount
                            : BigDecimal.ZERO;
                    return new ProjectActualRow(
                            rs.getObject("id", UUID.class),
                            rs.getDate("period_start").toLocalDate(),
                            rs.getString("account_code"),
                            rs.getString("debit_credit"),
                            accountingAmount,
                            classified,
                            rs.getString("currency"),
                            treatment,
                            nullableInteger(rs, "cost_rule_version"),
                            rs.getString("document_type"),
                            rs.getString("document_no"),
                            rs.getString("resolution_status"),
                            rs.getBoolean("is_cancelled"),
                            rs.getLong("journal_card_id"),
                            rs.getLong("journal_row_id"),
                            nullableInteger(rs, "action_type"),
                            nullableLong(rs, "action_id"),
                            sourceLedgerYear(rs.getString("source_partition")),
                            rs.getObject("synced_at", OffsetDateTime.class));
                }, actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to), limit);
    }

    @Transactional(readOnly = true)
    public ProjectActualSummary summary(
            BudgetActor actor,
            UUID bindingId,
            LocalDate from,
            LocalDate to) {
        tenantScope.apply(actor.tenantId());
        validateWindow(from, to);
        Binding binding = requireBinding(actor, bindingId);
        requireProjectAccess(actor, binding.externalProjectId());
        SummaryAmounts amounts = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(normalized_amount) FILTER (WHERE is_cancelled=FALSE),0)
                    AS accounting_actual,
                  COALESCE(SUM(normalized_amount) FILTER (
                    WHERE is_cancelled=FALSE
                      AND cost_treatment IN ('INCLUDE_COST','INCLUDE_NEGATIVE_COST')),0)
                    AS classified_cost,
                  COALESCE(SUM(normalized_amount) FILTER (
                    WHERE is_cancelled=FALSE
                      AND cost_treatment IN ('EXCLUDE_COUNTERPART','EXCLUDE_TRANSFER')),0)
                    AS excluded_amount,
                  COALESCE(SUM(normalized_amount) FILTER (
                    WHERE is_cancelled=FALSE AND cost_treatment='REQUIRES_REVIEW'),0)
                    AS requires_review_amount,
                  COUNT(*) FILTER (WHERE is_cancelled=FALSE) AS row_count,
                  COUNT(*) AS snapshot_row_count,
                  COUNT(*) FILTER (
                    WHERE is_cancelled=FALSE AND cost_treatment='REQUIRES_REVIEW')
                    AS requires_review_count,
                  COUNT(DISTINCT currency) FILTER (WHERE is_cancelled=FALSE) AS currency_count,
                  MIN(currency) FILTER (WHERE is_cancelled=FALSE) AS currency
                  FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND period_start BETWEEN ? AND ?
                """, (rs, rowNum) -> new SummaryAmounts(
                        rs.getBigDecimal("accounting_actual"),
                        rs.getBigDecimal("classified_cost"),
                        rs.getBigDecimal("excluded_amount"),
                        rs.getBigDecimal("requires_review_amount"),
                        rs.getLong("row_count"),
                        rs.getLong("snapshot_row_count"),
                        rs.getLong("requires_review_count"),
                        rs.getInt("currency_count") > 1 ? "MIXED" : rs.getString("currency")),
                actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to));
        Reconciliation latest = latestReconciliation(actor, binding.id(), from, to);
        OffsetDateTime lastSuccessfulSync = latestSuccessfulSync(actor, binding.id());
        return new ProjectActualSummary(
                binding.id(), from, to,
                amounts == null || amounts.currency() == null ? "N/A" : amounts.currency(),
                amounts == null ? BigDecimal.ZERO : amounts.accountingActual(),
                amounts == null ? BigDecimal.ZERO : amounts.classifiedCost(),
                amounts == null ? BigDecimal.ZERO : amounts.excludedAmount(),
                amounts == null ? BigDecimal.ZERO : amounts.requiresReviewAmount(),
                amounts == null ? 0 : amounts.rowCount(),
                amounts == null ? 0 : amounts.snapshotRowCount(),
                amounts == null ? 0 : amounts.requiresReviewCount(),
                latest == null ? "NOT_RECONCILED_FOR_WINDOW" : latest.status(),
                latest == null ? null : latest.difference(),
                lastSuccessfulSync);
    }

    private List<ProviderActualRow> fetchAll(
            String authorization,
            long companyId,
            Binding binding,
            LocalDate from,
            LocalDate to) {
        List<ProviderActualRow> rows = new ArrayList<>();
        String cursor = null;
        for (int pageNo = 0; pageNo < MAX_PROVIDER_PAGES; pageNo++) {
            ProviderActualPage page = provider.fetch(
                    authorization, companyId, binding.externalProjectId(),
                    from, to, cursor, PROVIDER_PAGE_SIZE);
            if (page == null || page.rows() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Actuals provider returned an invalid response");
            }
            rows.addAll(page.rows());
            if (!page.hasMore()) {
                return rows;
            }
            if (page.nextCursor() == null || page.nextCursor().isBlank()
                    || page.nextCursor().equals(cursor)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY, "Actuals provider cursor did not advance");
            }
            cursor = page.nextCursor();
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY, "Actuals provider exceeded the page safety limit");
    }

    private int upsertActual(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            ProviderActualRow row,
            List<Rule> rules) {
        String accountCode = trimToNull(row.accountCode());
        Treatment treatment = classify(row, accountCode, rules);
        List<ExistingSnapshot> existing = jdbc.query("""
                SELECT id, project_binding_id, source_hash, is_cancelled,
                       cost_treatment, cost_rule_version
                  FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND source_system=?
                   AND source_partition=? AND journal_row_id=?
                 FOR UPDATE
                """, (rs, rowNum) -> new ExistingSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_binding_id", UUID.class),
                        rs.getString("source_hash"),
                        rs.getBoolean("is_cancelled"),
                        rs.getString("cost_treatment"),
                        nullableInteger(rs, "cost_rule_version")),
                actor.tenantId(), actor.companyId(), row.sourceSystem(),
                sourcePartition(row), row.journalRowId());
        OffsetDateTime now = now();
        if (existing.isEmpty()) {
            UUID snapshotId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO actual_snapshots (
                      id, tenant_id, company_id, fiscal_year, period_start,
                      journal_card_id, journal_row_id, action_type, action_id,
                      resolution_status, direction, normalized_amount, currency,
                      source_hash, is_cancelled, synced_at, project_binding_id,
                      source_system, source_partition, account_code, debit_credit,
                      cost_treatment, cost_rule_version, document_type, document_no,
                      sync_batch_id, first_seen_at, last_seen_at
                    ) VALUES (
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """, snapshotId, actor.tenantId(), actor.companyId(),
                    row.postingDate().getYear(), Date.valueOf(row.postingDate()),
                    row.journalCardId(), row.journalRowId(), row.actionType(), row.actionId(),
                    row.resolutionStatus(), direction(row.debitCredit()), row.signedAmount(),
                    row.currency(), row.sourceHash(), row.cancelled(), now, binding.id(),
                    row.sourceSystem(), sourcePartition(row), accountCode, row.debitCredit(),
                    treatment.value(), treatment.ruleVersion(), row.documentType(),
                    trimToNull(row.documentNo()), batchId, now, now);
            insertVersion(
                    actor, snapshotId, batchId, 1, "FIRST_SEEN", row, treatment, now);
            return 1;
        }

        ExistingSnapshot current = existing.getFirst();
        if (!binding.id().equals(current.projectBindingId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Source accounting row is already assigned to another project binding");
        }
        boolean sourceChanged = !current.sourceHash().equals(row.sourceHash())
                || current.cancelled() != row.cancelled();
        boolean reclassified = !current.costTreatment().equals(treatment.value())
                || !java.util.Objects.equals(current.costRuleVersion(), treatment.ruleVersion());
        if (!sourceChanged && !reclassified) {
            jdbc.update("""
                    UPDATE actual_snapshots
                       SET last_seen_at=?, synced_at=?, sync_batch_id=?
                     WHERE id=? AND tenant_id=?
                    """, now, now, batchId, current.id(), actor.tenantId());
            return 0;
        }

        jdbc.update("""
                UPDATE actual_snapshots
                   SET fiscal_year=?, period_start=?,
                       journal_card_id=?, action_type=?, action_id=?, resolution_status=?,
                       direction=?, normalized_amount=?, currency=?, source_hash=?,
                       is_cancelled=?, synced_at=?, account_code=?, debit_credit=?,
                       cost_treatment=?, cost_rule_version=?, document_type=?, document_no=?,
                       sync_batch_id=?, last_seen_at=?
                 WHERE id=? AND tenant_id=?
                """, row.postingDate().getYear(), Date.valueOf(row.postingDate()),
                row.journalCardId(), row.actionType(), row.actionId(), row.resolutionStatus(),
                direction(row.debitCredit()), row.signedAmount(), row.currency(), row.sourceHash(),
                row.cancelled(), now, accountCode, row.debitCredit(), treatment.value(),
                treatment.ruleVersion(), row.documentType(), trimToNull(row.documentNo()),
                batchId, now, current.id(), actor.tenantId());
        int nextVersion = nextVersion(current.id());
        String reason = !current.cancelled() && row.cancelled()
                ? "TOMBSTONED"
                : sourceChanged ? "SOURCE_CHANGED" : "RECLASSIFIED";
        insertVersion(actor, current.id(), batchId, nextVersion, reason, row, treatment, now);
        return 1;
    }

    private int tombstoneMissing(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            LocalDate from,
            LocalDate to,
            Set<String> seenKeys) {
        List<TombstoneCandidate> candidates = jdbc.query("""
                SELECT id, source_partition, journal_row_id, source_hash,
                       normalized_amount, currency, debit_credit, account_code,
                       cost_treatment, cost_rule_version, resolution_status
                  FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND period_start BETWEEN ? AND ?
                   AND is_cancelled=FALSE
                 FOR UPDATE
                """, (rs, rowNum) -> new TombstoneCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_partition"),
                        rs.getLong("journal_row_id"),
                        rs.getString("source_hash"),
                        rs.getBigDecimal("normalized_amount"),
                        rs.getString("currency"),
                        rs.getString("debit_credit"),
                        rs.getString("account_code"),
                        rs.getString("cost_treatment"),
                        nullableInteger(rs, "cost_rule_version"),
                        rs.getString("resolution_status")),
                actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to));
        int count = 0;
        OffsetDateTime now = now();
        for (TombstoneCandidate candidate : candidates) {
            if (seenKeys.contains(sourceKey(
                    candidate.sourcePartition(), candidate.journalRowId()))) {
                continue;
            }
            jdbc.update("""
                    UPDATE actual_snapshots
                       SET is_cancelled=TRUE, synced_at=?, sync_batch_id=?, last_seen_at=?
                     WHERE id=? AND tenant_id=?
                    """, now, batchId, now, candidate.id(), actor.tenantId());
            int version = nextVersion(candidate.id());
            jdbc.update("""
                    INSERT INTO actual_snapshot_versions (
                      id, tenant_id, snapshot_id, version_no, sync_batch_id, recorded_reason,
                      source_hash, normalized_amount, currency, debit_credit, account_code,
                      cost_treatment, cost_rule_version, resolution_status, is_cancelled, recorded_at
                    ) VALUES (?, ?, ?, ?, ?, 'TOMBSTONED', ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                    """, UUID.randomUUID(), actor.tenantId(), candidate.id(), version, batchId,
                    candidate.sourceHash(), candidate.amount(), candidate.currency(),
                    candidate.debitCredit(), candidate.accountCode(), candidate.costTreatment(),
                    candidate.costRuleVersion(), candidate.resolutionStatus(), now);
            count++;
        }
        return count;
    }

    private void insertVersion(
            BudgetActor actor,
            UUID snapshotId,
            UUID batchId,
            int version,
            String reason,
            ProviderActualRow row,
            Treatment treatment,
            OffsetDateTime recordedAt) {
        jdbc.update("""
                INSERT INTO actual_snapshot_versions (
                  id, tenant_id, snapshot_id, version_no, sync_batch_id, recorded_reason,
                  source_hash, normalized_amount, currency, debit_credit, account_code,
                  cost_treatment, cost_rule_version, resolution_status, is_cancelled, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), snapshotId, version, batchId, reason,
                row.sourceHash(), row.signedAmount(), row.currency(), row.debitCredit(),
                trimToNull(row.accountCode()), treatment.value(), treatment.ruleVersion(),
                row.resolutionStatus(), row.cancelled(), recordedAt);
    }

    private int nextVersion(UUID snapshotId) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1
                  FROM actual_snapshot_versions
                 WHERE snapshot_id=?
                """, Integer.class, snapshotId);
        return next == null ? 1 : next;
    }

    private List<Rule> activeRules(BudgetActor actor) {
        return jdbc.query("""
                SELECT rs.version_no, r.priority, r.account_prefix,
                       r.debit_treatment, r.credit_treatment, r.document_type
                  FROM cost_rule_sets rs
                  JOIN cost_account_rules r
                    ON r.rule_set_id=rs.id AND r.tenant_id=rs.tenant_id
                 WHERE rs.tenant_id=? AND rs.company_id=? AND rs.status='ACTIVE'
                 ORDER BY r.priority
                """, (rs, rowNum) -> new Rule(
                        rs.getInt("version_no"),
                        rs.getInt("priority"),
                        rs.getString("account_prefix"),
                        rs.getString("debit_treatment"),
                        rs.getString("credit_treatment"),
                        rs.getString("document_type")),
                actor.tenantId(), actor.companyId());
    }

    private Treatment classify(
            ProviderActualRow row,
            String accountCode,
            List<Rule> rules) {
        if ("TRANSFER".equalsIgnoreCase(row.documentType())) {
            return new Treatment("EXCLUDE_TRANSFER", activeRuleVersion(rules));
        }
        if (accountCode == null) {
            return new Treatment("REQUIRES_REVIEW", activeRuleVersion(rules));
        }
        for (Rule rule : rules) {
            boolean accountMatches = accountCode.startsWith(rule.accountPrefix());
            boolean documentMatches = rule.documentType() == null
                    || rule.documentType().equalsIgnoreCase(row.documentType());
            if (accountMatches && documentMatches) {
                return new Treatment(
                        "DEBIT".equals(row.debitCredit())
                                ? rule.debitTreatment()
                                : rule.creditTreatment(),
                        rule.version());
            }
        }
        return new Treatment("REQUIRES_REVIEW", activeRuleVersion(rules));
    }

    private Integer activeRuleVersion(List<Rule> rules) {
        return rules.isEmpty() ? null : rules.getFirst().version();
    }

    private void validateSourceRow(
            Binding binding,
            ProjectActualSyncRequest request,
            ProviderActualRow row) {
        if (!binding.sourceSystem().equals(row.sourceSystem())
                || binding.externalCompanyNo() != row.sourceCompanyId()
                || binding.externalProjectId() != row.sourceProjectId()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Provider row escaped the requested source scope");
        }
        if (row.postingDate().isBefore(request.from())
                || row.postingDate().isAfter(request.to())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Provider row escaped the requested date window");
        }
        if (row.sourceLedgerYear() != row.postingDate().getYear()
                || row.journalRowId() < 1
                || row.journalCardId() < 1
                || row.signedAmount() == null
                || row.currency() == null
                || !row.currency().matches("[A-Z]{3}")
                || row.sourceHash() == null
                || !row.sourceHash().equals(providerHash(row))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, "Provider row failed integrity validation");
        }
    }

    private Binding requireBinding(BudgetActor actor, UUID bindingId) {
        List<Binding> rows = jdbc.query("""
                SELECT id, company_id, platform_project_ref, source_system,
                       external_company_no, external_project_id, external_project_code, verified_at
                  FROM budget_project_bindings
                 WHERE id=? AND tenant_id=? AND company_id=?
                """, (rs, rowNum) -> new Binding(
                        rs.getObject("id", UUID.class),
                        rs.getLong("company_id"),
                        rs.getString("platform_project_ref"),
                        rs.getString("source_system"),
                        rs.getLong("external_company_no"),
                        rs.getLong("external_project_id"),
                        rs.getString("external_project_code"),
                        rs.getObject("verified_at", OffsetDateTime.class)),
                bindingId, actor.tenantId(), actor.companyId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "Project binding not found in tenant scope");
        }
        return rows.getFirst();
    }

    private void requireProjectAccess(BudgetActor actor, long projectId) {
        if (!actor.canAccessProject(projectId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Project is outside the authoritative scope");
        }
    }

    private void validateWindow(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid actuals date window");
        }
        if (from.plusYears(10).isBefore(to)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Actuals date window exceeds ten years");
        }
    }

    private Reconciliation latestReconciliation(
            BudgetActor actor,
            UUID bindingId,
            LocalDate from,
            LocalDate to) {
        List<Reconciliation> rows = jdbc.query("""
                SELECT status, difference_amount, executed_at
                  FROM budget_reconciliation_runs
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND window_from=? AND window_to=?
                 ORDER BY executed_at DESC
                 LIMIT 1
                """, (rs, rowNum) -> new Reconciliation(
                        rs.getString("status"),
                        rs.getBigDecimal("difference_amount"),
                        rs.getObject("executed_at", OffsetDateTime.class)),
                actor.tenantId(), actor.companyId(), bindingId,
                Date.valueOf(from), Date.valueOf(to));
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private OffsetDateTime latestSuccessfulSync(
            BudgetActor actor,
            UUID bindingId) {
        return jdbc.queryForObject("""
                SELECT MAX(finished_at)
                  FROM actual_sync_batches
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND status IN ('MATCHED','DIFFERENCE')
                """, OffsetDateTime.class,
                actor.tenantId(), actor.companyId(), bindingId);
    }

    private String fingerprint(List<ProviderActualRow> rows) {
        String canonical = rows.stream()
                .sorted(Comparator.comparingInt(ProviderActualRow::sourceLedgerYear)
                        .thenComparingLong(ProviderActualRow::journalRowId))
                .map(row -> row.sourceLedgerYear() + ":" + row.journalRowId()
                        + ":" + row.sourceHash())
                .reduce("", (left, right) -> left + "|" + right);
        return sha256(canonical);
    }

    private String providerHash(ProviderActualRow row) {
        String canonical = String.join("|",
                row.sourceSystem(),
                Integer.toString(row.sourceLedgerYear()),
                Long.toString(row.sourceCompanyId()),
                Long.toString(row.sourceProjectId()),
                Long.toString(row.journalCardId()),
                Long.toString(row.journalRowId()),
                String.valueOf(row.postingDate()),
                String.valueOf(row.accountCode()),
                row.debitCredit(),
                row.signedAmount().toPlainString(),
                row.currency(),
                String.valueOf(row.actionType()),
                String.valueOf(row.actionId()),
                row.documentType(),
                String.valueOf(row.documentNo()),
                row.resolutionStatus(),
                Boolean.toString(row.cancelled()));
        return sha256(canonical);
    }

    private void audit(
            BudgetActor actor,
            String aggregateType,
            UUID aggregateId,
            String eventType,
            String payload) {
        jdbc.update("""
                INSERT INTO budget_audit_events (
                  id, tenant_id, aggregate_type, aggregate_id, event_type,
                  actor_id, payload_hash, created_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), aggregateType, aggregateId,
                eventType, actor.subject(), sha256(payload), now());
    }

    private ProjectBindingView bindingView(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ProjectBindingView(
                rs.getObject("id", UUID.class),
                rs.getLong("company_id"),
                rs.getString("platform_project_ref"),
                rs.getString("source_system"),
                rs.getLong("external_company_no"),
                rs.getLong("external_project_id"),
                rs.getString("external_project_code"),
                rs.getObject("verified_at", OffsetDateTime.class));
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static Integer nullableInteger(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column)
            throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String sourceKey(String partition, long journalRowId) {
        return partition + ":" + journalRowId;
    }

    private static String sourcePartition(ProviderActualRow row) {
        return "ledger-year:" + row.sourceLedgerYear();
    }

    private static int sourceLedgerYear(String sourcePartition) {
        if (sourcePartition == null || !sourcePartition.startsWith("ledger-year:")) {
            return 0;
        }
        try {
            return Integer.parseInt(sourcePartition.substring("ledger-year:".length()));
        } catch (NumberFormatException invalid) {
            return 0;
        }
    }

    private static String direction(String debitCredit) {
        return "CREDIT".equals(debitCredit) ? "INCOME" : "EXPENSE";
    }

    private static boolean isIncluded(String treatment) {
        return "INCLUDE_COST".equals(treatment)
                || "INCLUDE_NEGATIVE_COST".equals(treatment);
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String upperOrNull(String value) {
        String trimmed = trimToNull(value);
        return trimmed == null ? null : trimmed.toUpperCase(java.util.Locale.ROOT);
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private record Binding(
            UUID id,
            long companyId,
            String platformProjectRef,
            String sourceSystem,
            long externalCompanyNo,
            long externalProjectId,
            String externalProjectCode,
            OffsetDateTime verifiedAt) {
        ProjectBindingView view() {
            return new ProjectBindingView(
                    id, companyId, platformProjectRef, sourceSystem, externalCompanyNo,
                    externalProjectId, externalProjectCode, verifiedAt);
        }
    }

    private record SyncStart(
            UUID batchId,
            OffsetDateTime startedAt,
            Binding binding) {
    }

    private record Rule(
            int version,
            int priority,
            String accountPrefix,
            String debitTreatment,
            String creditTreatment,
            String documentType) {
    }

    private record Treatment(String value, Integer ruleVersion) {
    }

    private record ExistingSnapshot(
            UUID id,
            UUID projectBindingId,
            String sourceHash,
            boolean cancelled,
            String costTreatment,
            Integer costRuleVersion) {
    }

    private record TombstoneCandidate(
            UUID id,
            String sourcePartition,
            long journalRowId,
            String sourceHash,
            BigDecimal amount,
            String currency,
            String debitCredit,
            String accountCode,
            String costTreatment,
            Integer costRuleVersion,
            String resolutionStatus) {
    }

    private record SummaryAmounts(
            BigDecimal accountingActual,
            BigDecimal classifiedCost,
            BigDecimal excludedAmount,
            BigDecimal requiresReviewAmount,
            long rowCount,
            long snapshotRowCount,
            long requiresReviewCount,
            String currency) {
    }

    private record Reconciliation(
            String status,
            BigDecimal difference,
            OffsetDateTime executedAt) {
    }
}

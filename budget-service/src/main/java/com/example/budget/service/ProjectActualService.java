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
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(ProjectActualService.class);

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
        List<ProviderSourceLineRow> sourceLines;
        try {
            sourceRows = fetchAll(
                    authorization, actor.companyId(), start.binding(), request.from(), request.to());
            sourceLines = fetchAllSourceLines(
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
                return materialize(actor, start, request, sourceRows, sourceLines);
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
            List<ProviderActualRow> sourceRows,
            List<ProviderSourceLineRow> sourceLines) {
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
        SourceLineMaterialization sourceLineMaterialization = materializeSourceLines(
                actor, binding, batchId, request, sourceLines);
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
                && sourceLineMaterialization.unreconciledDocumentCount() == 0
                ? "MATCHED"
                : "DIFFERENCE";
        String fingerprint = fingerprint(sourceRows, sourceLines);
        OffsetDateTime finishedAt = now();

        jdbc.update("""
                UPDATE actual_sync_batches
                   SET status=?, source_row_count=?, changed_row_count=?,
                       tombstone_row_count=?, source_amount=?, snapshot_amount=?,
                       source_fingerprint=?, finished_at=?,
                       source_document_count=?, source_line_count=?,
                       changed_source_line_count=?, tombstone_source_line_count=?
                 WHERE id=? AND tenant_id=?
                """, status, sourceRows.size(), changed, tombstones,
                sourceAmount, snapshotAmount, fingerprint, finishedAt,
                sourceLineMaterialization.documentCount(),
                sourceLines.size(),
                sourceLineMaterialization.changedLineCount(),
                sourceLineMaterialization.tombstoneLineCount(),
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
                "status=" + status + ";changed=" + changed + ";tombstones=" + tombstones
                        + ";sourceLines=" + sourceLines.size()
                        + ";changedSourceLines="
                        + sourceLineMaterialization.changedLineCount());
        return new ProjectActualSyncResult(
                batchId, status, null, sourceRows.size(), changed, tombstones,
                sourceAmount, snapshotAmount, difference, fingerprint, finishedAt,
                sourceLineMaterialization.documentCount(),
                sourceLines.size(),
                sourceLineMaterialization.changedLineCount(),
                sourceLineMaterialization.tombstoneLineCount());
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
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, finishedAt,
                0, 0, 0, 0);
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
    public List<ProjectActualSourceLineRow> sourceLines(
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
                SELECT l.id, l.source_document_id, d.document_date, d.document_type,
                       d.document_kind, d.document_no, d.external_document_id,
                       l.external_line_id, l.line_ordinal, l.product_name,
                       l.line_description, l.quantity, l.unit_code, l.unit_price,
                       l.net_amount, l.tax_rate, l.tax_amount, l.gross_amount,
                       l.cost_basis_amount, l.currency, l.account_code,
                       l.line_match_status, d.reconciliation_status,
                       d.accounting_cost_total, d.reconciliation_difference,
                       d.accounting_row_count, l.is_cancelled, l.synced_at
                  FROM actual_source_lines l
                  JOIN actual_source_documents d
                    ON d.id=l.source_document_id AND d.tenant_id=l.tenant_id
                 WHERE l.tenant_id=? AND l.company_id=? AND l.project_binding_id=?
                   AND d.document_date BETWEEN ? AND ?
                 ORDER BY d.document_date DESC, d.external_document_id,
                          l.line_ordinal, l.external_line_id
                 LIMIT ?
                """, (rs, rowNum) -> sourceLineRow(rs),
                actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to), limit);
    }

    @Transactional(readOnly = true)
    public ProjectActualSourceDocumentDetail sourceDocument(
            BudgetActor actor,
            UUID bindingId,
            UUID sourceDocumentId) {
        tenantScope.apply(actor.tenantId());
        Binding binding = requireBinding(actor, bindingId);
        requireProjectAccess(actor, binding.externalProjectId());
        List<SourceDocumentView> documents = jdbc.query("""
                SELECT id, document_date, document_type, document_kind, document_no,
                       external_document_id, currency, source_line_total,
                       accounting_cost_total, reconciliation_difference,
                       reconciliation_status, accounting_row_count, is_cancelled,
                       synced_at, source_partition
                  FROM actual_source_documents
                 WHERE id=? AND tenant_id=? AND company_id=? AND project_binding_id=?
                """, (rs, rowNum) -> new SourceDocumentView(
                        rs.getObject("id", UUID.class),
                        rs.getDate("document_date").toLocalDate(),
                        rs.getString("document_type"),
                        rs.getString("document_kind"),
                        rs.getString("document_no"),
                        rs.getLong("external_document_id"),
                        rs.getString("currency"),
                        rs.getBigDecimal("source_line_total"),
                        rs.getBigDecimal("accounting_cost_total"),
                        rs.getBigDecimal("reconciliation_difference"),
                        rs.getString("reconciliation_status"),
                        rs.getInt("accounting_row_count"),
                        rs.getBoolean("is_cancelled"),
                        rs.getObject("synced_at", OffsetDateTime.class),
                        rs.getString("source_partition")),
                sourceDocumentId, actor.tenantId(), actor.companyId(), binding.id());
        if (documents.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "Source document not found in project scope");
        }
        SourceDocumentView document = documents.getFirst();
        List<ProjectActualSourceLineRow> lines = jdbc.query("""
                SELECT l.id, l.source_document_id, d.document_date, d.document_type,
                       d.document_kind, d.document_no, d.external_document_id,
                       l.external_line_id, l.line_ordinal, l.product_name,
                       l.line_description, l.quantity, l.unit_code, l.unit_price,
                       l.net_amount, l.tax_rate, l.tax_amount, l.gross_amount,
                       l.cost_basis_amount, l.currency, l.account_code,
                       l.line_match_status, d.reconciliation_status,
                       d.accounting_cost_total, d.reconciliation_difference,
                       d.accounting_row_count, l.is_cancelled, l.synced_at
                  FROM actual_source_lines l
                  JOIN actual_source_documents d
                    ON d.id=l.source_document_id AND d.tenant_id=l.tenant_id
                 WHERE l.tenant_id=? AND l.company_id=? AND l.project_binding_id=?
                   AND l.source_document_id=?
                 ORDER BY l.line_ordinal, l.external_line_id
                """, (rs, rowNum) -> sourceLineRow(rs),
                actor.tenantId(), actor.companyId(), binding.id(), sourceDocumentId);
        List<ProjectActualRow> accountingRows = jdbc.query("""
                SELECT id, period_start, account_code, debit_credit, normalized_amount,
                       currency, cost_treatment, cost_rule_version, document_type, document_no,
                       resolution_status, is_cancelled, journal_card_id, journal_row_id,
                       action_type, action_id, source_partition, synced_at
                  FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND source_partition=? AND document_type=?
                   AND source_document_external_id=?
                 ORDER BY period_start, journal_row_id
                """, (rs, rowNum) -> actualRow(rs),
                actor.tenantId(), actor.companyId(), binding.id(),
                document.sourcePartition(), document.documentType(),
                document.externalDocumentId());
        return new ProjectActualSourceDocumentDetail(
                document.id(),
                document.documentDate(),
                document.documentType(),
                document.documentKind(),
                document.documentNo(),
                document.externalDocumentId(),
                document.currency(),
                document.sourceLineTotal(),
                document.accountingCostTotal(),
                document.reconciliationDifference(),
                document.reconciliationStatus(),
                document.accountingRowCount(),
                document.cancelled(),
                document.syncedAt(),
                lines,
                accountingRows);
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
        SourceLineSummary sourceLineSummary = jdbc.queryForObject("""
                SELECT
                  COALESCE(SUM(l.cost_basis_amount) FILTER (
                    WHERE l.is_cancelled=FALSE
                      AND d.document_kind IN ('PURCHASE_INVOICE','PURCHASE_RETURN')),0)
                    AS source_line_actual,
                  COUNT(DISTINCT d.id) FILTER (WHERE d.is_cancelled=FALSE)
                    AS source_document_count,
                  COUNT(*) FILTER (WHERE l.is_cancelled=FALSE)
                    AS source_line_count,
                  COUNT(*) FILTER (
                    WHERE l.is_cancelled=FALSE
                      AND l.line_match_status='UNRESOLVED')
                    AS unresolved_source_line_count
                  FROM actual_source_documents d
                  LEFT JOIN actual_source_lines l
                    ON l.source_document_id=d.id AND l.tenant_id=d.tenant_id
                 WHERE d.tenant_id=? AND d.company_id=? AND d.project_binding_id=?
                   AND d.document_date BETWEEN ? AND ?
                """, (rs, rowNum) -> new SourceLineSummary(
                        rs.getBigDecimal("source_line_actual"),
                        rs.getLong("source_document_count"),
                        rs.getLong("source_line_count"),
                        rs.getLong("unresolved_source_line_count")),
                actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to));
        BigDecimal nonInvoiceActual = amount("""
                SELECT COALESCE(SUM(a.normalized_amount),0)
                  FROM actual_snapshots a
                 WHERE a.tenant_id=? AND a.company_id=? AND a.project_binding_id=?
                   AND a.period_start BETWEEN ? AND ?
                   AND a.is_cancelled=FALSE
                   AND a.cost_treatment IN ('INCLUDE_COST','INCLUDE_NEGATIVE_COST')
                   AND (
                     a.document_type <> 'INVOICE'
                     OR a.source_document_external_id IS NULL
                     OR NOT EXISTS (
                       SELECT 1
                         FROM actual_source_documents d
                        WHERE d.tenant_id=a.tenant_id
                          AND d.company_id=a.company_id
                          AND d.project_binding_id=a.project_binding_id
                          AND d.source_partition=a.source_partition
                          AND d.document_type=a.document_type
                          AND d.external_document_id=a.source_document_external_id
                          AND d.is_cancelled=FALSE
                     )
                   )
                """, actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to));
        BigDecimal sourceLineActual = sourceLineSummary == null
                || sourceLineSummary.sourceLineActual() == null
                        ? BigDecimal.ZERO
                        : sourceLineSummary.sourceLineActual();
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
                lastSuccessfulSync,
                sourceLineActual,
                nonInvoiceActual,
                sourceLineActual.add(nonInvoiceActual),
                sourceLineSummary == null ? 0 : sourceLineSummary.sourceDocumentCount(),
                sourceLineSummary == null ? 0 : sourceLineSummary.sourceLineCount(),
                sourceLineSummary == null ? 0 : sourceLineSummary.unresolvedSourceLineCount());
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

    private List<ProviderSourceLineRow> fetchAllSourceLines(
            String authorization,
            long companyId,
            Binding binding,
            LocalDate from,
            LocalDate to) {
        List<ProviderSourceLineRow> rows = new ArrayList<>();
        String cursor = null;
        for (int pageNo = 0; pageNo < MAX_PROVIDER_PAGES; pageNo++) {
            ProviderSourceLinePage page = provider.fetchSourceLines(
                    authorization, companyId, binding.externalProjectId(),
                    from, to, cursor, PROVIDER_PAGE_SIZE);
            if (page == null || page.rows() == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Source-line provider returned an invalid response");
            }
            rows.addAll(page.rows());
            if (!page.hasMore()) {
                return rows;
            }
            if (page.nextCursor() == null || page.nextCursor().isBlank()
                    || page.nextCursor().equals(cursor)) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Source-line provider cursor did not advance");
            }
            cursor = page.nextCursor();
        }
        throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "Source-line provider exceeded the page safety limit");
    }

    private SourceLineMaterialization materializeSourceLines(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            ProjectActualSyncRequest request,
            List<ProviderSourceLineRow> sourceLines) {
        Map<SourceDocumentKey, List<ProviderSourceLineRow>> documents = new LinkedHashMap<>();
        Set<String> seenLineKeys = new HashSet<>();
        for (ProviderSourceLineRow line : sourceLines) {
            validateSourceLine(binding, request, line);
            String partition = sourcePartition(line);
            if (!seenLineKeys.add(sourceLineKey(partition, line.sourceLineId()))) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_GATEWAY,
                        "Provider returned a duplicate source-document line");
            }
            SourceDocumentKey documentKey = new SourceDocumentKey(
                    partition, line.documentType(), line.sourceDocumentId());
            documents.computeIfAbsent(documentKey, ignored -> new ArrayList<>()).add(line);
        }

        int changedLines = 0;
        for (Map.Entry<SourceDocumentKey, List<ProviderSourceLineRow>> entry
                : documents.entrySet()) {
            List<ProviderSourceLineRow> documentLines = entry.getValue().stream()
                    .sorted(Comparator.comparingInt(ProviderSourceLineRow::lineOrdinal)
                            .thenComparingLong(ProviderSourceLineRow::sourceLineId))
                    .toList();
            validateDocumentConsistency(documentLines);
            UUID documentId = upsertSourceDocument(
                    actor, binding, batchId, entry.getKey(), documentLines);
            for (ProviderSourceLineRow line : documentLines) {
                changedLines += upsertSourceLine(
                        actor, binding, batchId, documentId, line);
            }
        }

        int tombstoneLines = tombstoneMissingSourceLines(
                actor, binding, batchId, request.from(), request.to(), seenLineKeys);
        int unreconciledDocuments = reconcileSourceDocuments(
                actor, binding, batchId, request.from(), request.to());
        return new SourceLineMaterialization(
                documents.size(), changedLines, tombstoneLines, unreconciledDocuments);
    }

    private UUID upsertSourceDocument(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            SourceDocumentKey key,
            List<ProviderSourceLineRow> lines) {
        ProviderSourceLineRow first = lines.getFirst();
        String sourceHash = sourceLineFingerprint(lines);
        boolean cancelled = lines.stream().allMatch(ProviderSourceLineRow::cancelled);
        List<UUID> existing = jdbc.query("""
                SELECT id
                  FROM actual_source_documents
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND source_system=? AND source_partition=? AND document_type=?
                   AND external_document_id=?
                 FOR UPDATE
                """, (rs, rowNum) -> rs.getObject("id", UUID.class),
                actor.tenantId(), actor.companyId(), binding.id(),
                first.sourceSystem(), key.sourcePartition(), key.documentType(),
                key.externalDocumentId());
        OffsetDateTime syncedAt = now();
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO actual_source_documents (
                      id, tenant_id, company_id, project_binding_id, source_system,
                      source_partition, document_type, external_document_id, document_no,
                      document_date, document_kind, currency, source_hash, is_cancelled,
                      sync_batch_id, first_seen_at, last_seen_at, synced_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, id, actor.tenantId(), actor.companyId(), binding.id(),
                    first.sourceSystem(), key.sourcePartition(), key.documentType(),
                    key.externalDocumentId(), trimToNull(first.documentNo()),
                    Date.valueOf(first.documentDate()), first.documentKind(),
                    first.currency(), sourceHash, cancelled, batchId,
                    syncedAt, syncedAt, syncedAt);
            return id;
        }

        UUID id = existing.getFirst();
        jdbc.update("""
                UPDATE actual_source_documents
                   SET document_no=?, document_date=?, document_kind=?, currency=?,
                       source_hash=?, is_cancelled=?, sync_batch_id=?,
                       last_seen_at=?, synced_at=?
                 WHERE id=? AND tenant_id=? AND company_id=? AND project_binding_id=?
                """, trimToNull(first.documentNo()), Date.valueOf(first.documentDate()),
                first.documentKind(), first.currency(), sourceHash, cancelled,
                batchId, syncedAt, syncedAt, id, actor.tenantId(),
                actor.companyId(), binding.id());
        return id;
    }

    private int upsertSourceLine(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            UUID documentId,
            ProviderSourceLineRow line) {
        List<ExistingSourceLine> existing = jdbc.query("""
                SELECT id, project_binding_id, source_hash, is_cancelled,
                       line_match_status
                  FROM actual_source_lines
                 WHERE tenant_id=? AND company_id=? AND source_document_id=?
                   AND external_line_id=?
                 FOR UPDATE
                """, (rs, rowNum) -> new ExistingSourceLine(
                        rs.getObject("id", UUID.class),
                        rs.getObject("project_binding_id", UUID.class),
                        rs.getString("source_hash"),
                        rs.getBoolean("is_cancelled"),
                        rs.getString("line_match_status")),
                actor.tenantId(), actor.companyId(), documentId, line.sourceLineId());
        BigDecimal costBasis = sourceLineCostBasis(line);
        OffsetDateTime syncedAt = now();
        if (existing.isEmpty()) {
            UUID id = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO actual_source_lines (
                      id, tenant_id, company_id, project_binding_id, source_document_id,
                      external_line_id, line_ordinal, product_name, line_description,
                      quantity, unit_code, unit_price, net_amount, tax_rate, tax_amount,
                      gross_amount, cost_basis_amount, currency, account_code,
                      line_match_status, source_hash, is_cancelled, sync_batch_id,
                      first_seen_at, last_seen_at, synced_at
                    ) VALUES (
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?
                    )
                    """, id, actor.tenantId(), actor.companyId(), binding.id(), documentId,
                    line.sourceLineId(), line.lineOrdinal(), trimToNull(line.productName()),
                    trimToNull(line.description()), line.quantity(), trimToNull(line.unit()),
                    line.unitPrice(), line.netAmount(), line.taxRate(), line.taxAmount(),
                    line.grossAmount(), costBasis, line.currency(),
                    trimToNull(line.accountCode()), "UNRESOLVED", line.sourceHash(),
                    line.cancelled(), batchId, syncedAt, syncedAt, syncedAt);
            insertSourceLineVersion(
                    actor, id, batchId, 1, "FIRST_SEEN", line, costBasis,
                    "UNRESOLVED", syncedAt);
            return 1;
        }

        ExistingSourceLine current = existing.getFirst();
        if (!binding.id().equals(current.projectBindingId())) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Source-document line is already assigned to another project binding");
        }
        boolean sourceChanged = !current.sourceHash().equals(line.sourceHash())
                || current.cancelled() != line.cancelled();
        if (!sourceChanged) {
            jdbc.update("""
                    UPDATE actual_source_lines
                       SET last_seen_at=?, synced_at=?, sync_batch_id=?
                     WHERE id=? AND tenant_id=?
                    """, syncedAt, syncedAt, batchId, current.id(), actor.tenantId());
            return 0;
        }

        jdbc.update("""
                UPDATE actual_source_lines
                   SET line_ordinal=?, product_name=?, line_description=?,
                       quantity=?, unit_code=?, unit_price=?, net_amount=?, tax_rate=?,
                       tax_amount=?, gross_amount=?, cost_basis_amount=?, currency=?,
                       account_code=?, source_hash=?, is_cancelled=?, sync_batch_id=?,
                       last_seen_at=?, synced_at=?
                 WHERE id=? AND tenant_id=?
                """, line.lineOrdinal(), trimToNull(line.productName()),
                trimToNull(line.description()), line.quantity(), trimToNull(line.unit()),
                line.unitPrice(), line.netAmount(), line.taxRate(), line.taxAmount(),
                line.grossAmount(), costBasis, line.currency(),
                trimToNull(line.accountCode()), line.sourceHash(), line.cancelled(),
                batchId, syncedAt, syncedAt, current.id(), actor.tenantId());
        int nextVersion = nextSourceLineVersion(current.id());
        String reason = !current.cancelled() && line.cancelled()
                ? "TOMBSTONED"
                : "SOURCE_CHANGED";
        insertSourceLineVersion(
                actor, current.id(), batchId, nextVersion, reason, line,
                costBasis, current.lineMatchStatus(), syncedAt);
        return 1;
    }

    private int tombstoneMissingSourceLines(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            LocalDate from,
            LocalDate to,
            Set<String> seenLineKeys) {
        List<SourceLineTombstoneCandidate> candidates = jdbc.query("""
                SELECT l.id, d.source_partition, l.external_line_id, l.source_hash,
                       l.net_amount, l.tax_amount, l.gross_amount, l.cost_basis_amount,
                       l.currency, l.line_match_status
                  FROM actual_source_lines l
                  JOIN actual_source_documents d
                    ON d.id=l.source_document_id AND d.tenant_id=l.tenant_id
                 WHERE l.tenant_id=? AND l.company_id=? AND l.project_binding_id=?
                   AND d.document_date BETWEEN ? AND ?
                   AND l.is_cancelled=FALSE
                 FOR UPDATE OF l
                """, (rs, rowNum) -> new SourceLineTombstoneCandidate(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_partition"),
                        rs.getLong("external_line_id"),
                        rs.getString("source_hash"),
                        rs.getBigDecimal("net_amount"),
                        rs.getBigDecimal("tax_amount"),
                        rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("cost_basis_amount"),
                        rs.getString("currency"),
                        rs.getString("line_match_status")),
                actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to));
        int count = 0;
        OffsetDateTime recordedAt = now();
        for (SourceLineTombstoneCandidate candidate : candidates) {
            if (seenLineKeys.contains(sourceLineKey(
                    candidate.sourcePartition(), candidate.externalLineId()))) {
                continue;
            }
            jdbc.update("""
                    UPDATE actual_source_lines
                       SET is_cancelled=TRUE, sync_batch_id=?,
                           last_seen_at=?, synced_at=?
                     WHERE id=? AND tenant_id=?
                    """, batchId, recordedAt, recordedAt,
                    candidate.id(), actor.tenantId());
            insertSourceLineVersion(
                    actor,
                    candidate.id(),
                    batchId,
                    nextSourceLineVersion(candidate.id()),
                    "TOMBSTONED",
                    candidate.sourceHash(),
                    candidate.netAmount(),
                    candidate.taxAmount(),
                    candidate.grossAmount(),
                    candidate.costBasisAmount(),
                    candidate.currency(),
                    candidate.lineMatchStatus(),
                    true,
                    recordedAt);
            count++;
        }
        return count;
    }

    private int reconcileSourceDocuments(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            LocalDate from,
            LocalDate to) {
        List<SourceDocumentSnapshot> documents = jdbc.query("""
                SELECT id, source_partition, document_type, external_document_id,
                       document_kind
                  FROM actual_source_documents
                 WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                   AND document_date BETWEEN ? AND ?
                 FOR UPDATE
                """, (rs, rowNum) -> new SourceDocumentSnapshot(
                        rs.getObject("id", UUID.class),
                        rs.getString("source_partition"),
                        rs.getString("document_type"),
                        rs.getLong("external_document_id"),
                        rs.getString("document_kind")),
                actor.tenantId(), actor.companyId(), binding.id(),
                Date.valueOf(from), Date.valueOf(to));

        int unreconciled = 0;
        OffsetDateTime reconciledAt = now();
        for (SourceDocumentSnapshot document : documents) {
            BigDecimal sourceLineTotal = amount("""
                    SELECT COALESCE(SUM(cost_basis_amount),0)
                      FROM actual_source_lines
                     WHERE tenant_id=? AND company_id=? AND source_document_id=?
                       AND is_cancelled=FALSE
                    """, actor.tenantId(), actor.companyId(), document.id());
            BigDecimal accountingCostTotal = amount("""
                    SELECT COALESCE(SUM(normalized_amount),0)
                      FROM actual_snapshots
                     WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                       AND source_partition=? AND document_type=?
                       AND source_document_external_id=?
                       AND cost_treatment IN ('INCLUDE_COST','INCLUDE_NEGATIVE_COST')
                       AND is_cancelled=FALSE
                    """, actor.tenantId(), actor.companyId(), binding.id(),
                    document.sourcePartition(), document.documentType(),
                    document.externalDocumentId());
            long accountingRowCount = count("""
                    SELECT COUNT(*)
                      FROM actual_snapshots
                     WHERE tenant_id=? AND company_id=? AND project_binding_id=?
                       AND source_partition=? AND document_type=?
                       AND source_document_external_id=?
                       AND is_cancelled=FALSE
                    """, actor.tenantId(), actor.companyId(), binding.id(),
                    document.sourcePartition(), document.documentType(),
                    document.externalDocumentId());
            long activeLineCount = count("""
                    SELECT COUNT(*)
                      FROM actual_source_lines
                     WHERE tenant_id=? AND company_id=? AND source_document_id=?
                       AND is_cancelled=FALSE
                    """, actor.tenantId(), actor.companyId(), document.id());
            BigDecimal difference = sourceLineTotal.subtract(accountingCostTotal);
            String reconciliationStatus;
            if (activeLineCount == 0) {
                reconciliationStatus = "UNRESOLVED";
            } else if (accountingRowCount == 0) {
                reconciliationStatus = "NO_ACCOUNTING";
            } else if (difference.abs().compareTo(RECONCILIATION_TOLERANCE) <= 0) {
                reconciliationStatus = "RECONCILED";
            } else {
                reconciliationStatus = "DIFFERENCE";
            }
            boolean cancelled = activeLineCount == 0;
            jdbc.update("""
                    UPDATE actual_source_documents
                       SET source_line_total=?, accounting_cost_total=?,
                           reconciliation_difference=?, reconciliation_status=?,
                           accounting_row_count=?, is_cancelled=?,
                           sync_batch_id=?, synced_at=?
                     WHERE id=? AND tenant_id=?
                    """, sourceLineTotal, accountingCostTotal, difference,
                    reconciliationStatus, accountingRowCount, cancelled,
                    batchId, reconciledAt, document.id(), actor.tenantId());
            reconcileSourceDocumentLines(
                    actor, binding, batchId, document, reconciliationStatus, reconciledAt);
            if (!cancelled
                    && isCostDocument(document.documentKind())
                    && !"RECONCILED".equals(reconciliationStatus)) {
                unreconciled++;
            }
        }
        return unreconciled;
    }

    private void reconcileSourceDocumentLines(
            BudgetActor actor,
            Binding binding,
            UUID batchId,
            SourceDocumentSnapshot document,
            String documentStatus,
            OffsetDateTime recordedAt) {
        List<ReconciledSourceLine> lines = jdbc.query("""
                SELECT l.id, l.external_line_id, l.source_hash, l.net_amount,
                       l.tax_amount, l.gross_amount, l.cost_basis_amount, l.currency,
                       l.line_match_status, l.is_cancelled,
                       EXISTS (
                           SELECT 1
                             FROM actual_snapshots a
                            WHERE a.tenant_id=l.tenant_id
                              AND a.company_id=l.company_id
                              AND a.project_binding_id=l.project_binding_id
                              AND a.source_partition=?
                              AND a.document_type=?
                              AND a.source_document_external_id=?
                              AND a.source_document_line_external_id=l.external_line_id
                              AND a.is_cancelled=FALSE
                       ) AS exact_match
                  FROM actual_source_lines l
                 WHERE l.tenant_id=? AND l.company_id=? AND l.source_document_id=?
                 FOR UPDATE
                """, (rs, rowNum) -> new ReconciledSourceLine(
                        rs.getObject("id", UUID.class),
                        rs.getLong("external_line_id"),
                        rs.getString("source_hash"),
                        rs.getBigDecimal("net_amount"),
                        rs.getBigDecimal("tax_amount"),
                        rs.getBigDecimal("gross_amount"),
                        rs.getBigDecimal("cost_basis_amount"),
                        rs.getString("currency"),
                        rs.getString("line_match_status"),
                        rs.getBoolean("is_cancelled"),
                        rs.getBoolean("exact_match")),
                document.sourcePartition(), document.documentType(),
                document.externalDocumentId(), actor.tenantId(),
                actor.companyId(), document.id());
        for (ReconciledSourceLine line : lines) {
            if (line.cancelled()
                    || "MANUALLY_CONFIRMED".equals(line.currentStatus())
                    || "PROPOSED".equals(line.currentStatus())) {
                continue;
            }
            String nextStatus = line.exactMatch()
                    ? "EXACT_SOURCE_LINE"
                    : "RECONCILED".equals(documentStatus)
                            ? "RECONCILED"
                            : "UNRESOLVED";
            if (nextStatus.equals(line.currentStatus())) {
                continue;
            }
            jdbc.update("""
                    UPDATE actual_source_lines
                       SET line_match_status=?, sync_batch_id=?, synced_at=?
                     WHERE id=? AND tenant_id=? AND project_binding_id=?
                    """, nextStatus, batchId, recordedAt, line.id(),
                    actor.tenantId(), binding.id());
            insertSourceLineVersion(
                    actor,
                    line.id(),
                    batchId,
                    nextSourceLineVersion(line.id()),
                    "RECONCILED".equals(nextStatus)
                            || "EXACT_SOURCE_LINE".equals(nextStatus)
                                    ? "RECONCILED"
                                    : "RECONCILIATION_CHANGED",
                    line.sourceHash(),
                    line.netAmount(),
                    line.taxAmount(),
                    line.grossAmount(),
                    line.costBasisAmount(),
                    line.currency(),
                    nextStatus,
                    line.cancelled(),
                    recordedAt);
        }
    }

    private void insertSourceLineVersion(
            BudgetActor actor,
            UUID sourceLineId,
            UUID batchId,
            int version,
            String reason,
            ProviderSourceLineRow line,
            BigDecimal costBasis,
            String lineMatchStatus,
            OffsetDateTime recordedAt) {
        insertSourceLineVersion(
                actor, sourceLineId, batchId, version, reason,
                line.sourceHash(), line.netAmount(), line.taxAmount(),
                line.grossAmount(), costBasis, line.currency(), lineMatchStatus,
                line.cancelled(), recordedAt);
    }

    private void insertSourceLineVersion(
            BudgetActor actor,
            UUID sourceLineId,
            UUID batchId,
            int version,
            String reason,
            String sourceHash,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal grossAmount,
            BigDecimal costBasisAmount,
            String currency,
            String lineMatchStatus,
            boolean cancelled,
            OffsetDateTime recordedAt) {
        jdbc.update("""
                INSERT INTO actual_source_line_versions (
                  id, tenant_id, source_line_id, version_no, sync_batch_id,
                  recorded_reason, source_hash, net_amount, tax_amount, gross_amount,
                  cost_basis_amount, currency, line_match_status, is_cancelled, recorded_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), sourceLineId, version, batchId,
                reason, sourceHash, netAmount, taxAmount, grossAmount, costBasisAmount,
                currency, lineMatchStatus, cancelled, recordedAt);
    }

    private int nextSourceLineVersion(UUID sourceLineId) {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(version_no),0)+1
                  FROM actual_source_line_versions
                 WHERE source_line_id=?
                """, Integer.class, sourceLineId);
        return next == null ? 1 : next;
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
                      sync_batch_id, first_seen_at, last_seen_at,
                      source_document_external_id, source_document_line_external_id
                    ) VALUES (
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                      ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """, snapshotId, actor.tenantId(), actor.companyId(),
                    row.postingDate().getYear(), Date.valueOf(row.postingDate()),
                    row.journalCardId(), row.journalRowId(), row.actionType(), row.actionId(),
                    row.resolutionStatus(), direction(row.debitCredit()), row.signedAmount(),
                    row.currency(), row.sourceHash(), row.cancelled(), now, binding.id(),
                    row.sourceSystem(), sourcePartition(row), accountCode, row.debitCredit(),
                    treatment.value(), treatment.ruleVersion(), row.documentType(),
                    trimToNull(row.documentNo()), batchId, now, now,
                    row.actionId(), row.actionRowId());
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
                       sync_batch_id=?, last_seen_at=?,
                       source_document_external_id=?, source_document_line_external_id=?
                 WHERE id=? AND tenant_id=?
                """, row.postingDate().getYear(), Date.valueOf(row.postingDate()),
                row.journalCardId(), row.actionType(), row.actionId(), row.resolutionStatus(),
                direction(row.debitCredit()), row.signedAmount(), row.currency(), row.sourceHash(),
                row.cancelled(), now, accountCode, row.debitCredit(), treatment.value(),
                treatment.ruleVersion(), row.documentType(), trimToNull(row.documentNo()),
                batchId, now, row.actionId(), row.actionRowId(),
                current.id(), actor.tenantId());
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

    private void validateSourceLine(
            Binding binding,
            ProjectActualSyncRequest request,
            ProviderSourceLineRow line) {
        if (!binding.sourceSystem().equals(line.sourceSystem())
                || binding.externalCompanyNo() != line.sourceCompanyId()
                || binding.externalProjectId() != line.sourceProjectId()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Provider source line escaped the requested source scope");
        }
        if (line.documentDate() == null
                || line.documentDate().isBefore(request.from())
                || line.documentDate().isAfter(request.to())) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Provider source line escaped the requested date window");
        }
        if (line.sourceLedgerYear() != line.documentDate().getYear()
                || line.sourceDocumentId() < 1
                || line.sourceLineId() < 1
                || line.lineOrdinal() < 1
                || !"INVOICE".equals(line.documentType())
                || !Set.of(
                        "PURCHASE_INVOICE",
                        "PURCHASE_RETURN",
                        "SALES_INVOICE",
                        "SALES_RETURN",
                        "OTHER_INVOICE").contains(line.documentKind())
                || line.netAmount() == null
                || line.taxAmount() == null
                || line.grossAmount() == null
                || line.currency() == null
                || !line.currency().matches("[A-Z]{3}")
                || "XXX".equals(line.currency())
                || line.sourceHash() == null
                || !line.sourceHash().equals(providerSourceLineHash(line))) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Provider source line failed integrity validation");
        }
        if (line.documentNo() != null && line.documentNo().length() > 160
                || line.productName() != null && line.productName().length() > 500
                || line.unit() != null && line.unit().length() > 40
                || line.accountCode() != null && line.accountCode().length() > 80) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Provider source line exceeded the normalized field limits");
        }
    }

    private void validateDocumentConsistency(List<ProviderSourceLineRow> lines) {
        ProviderSourceLineRow first = lines.getFirst();
        boolean consistent = lines.stream().allMatch(line ->
                line.sourceSystem().equals(first.sourceSystem())
                        && line.sourceLedgerYear() == first.sourceLedgerYear()
                        && line.sourceCompanyId() == first.sourceCompanyId()
                        && line.sourceProjectId() == first.sourceProjectId()
                        && line.sourceDocumentId() == first.sourceDocumentId()
                        && line.documentDate().equals(first.documentDate())
                        && line.documentType().equals(first.documentType())
                        && line.documentKind().equals(first.documentKind())
                        && java.util.Objects.equals(line.documentNo(), first.documentNo())
                        && line.currency().equals(first.currency()));
        if (!consistent) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Provider returned inconsistent source-document headers");
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
            log.warn(
                    "budget_authorization_denied reason=project_scope companyId={} projectId={} "
                            + "superAdmin={} allowedProjectCount={}",
                    actor.companyId(),
                    projectId,
                    actor.superAdmin(),
                    actor.allowedProjectIds().size());
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

    private String fingerprint(
            List<ProviderActualRow> rows,
            List<ProviderSourceLineRow> sourceLines) {
        String accounting = rows.stream()
                .sorted(Comparator.comparingInt(ProviderActualRow::sourceLedgerYear)
                        .thenComparingLong(ProviderActualRow::journalRowId))
                .map(row -> row.sourceLedgerYear() + ":" + row.journalRowId()
                        + ":" + row.sourceHash())
                .reduce("", (left, right) -> left + "|" + right);
        String documents = sourceLines.stream()
                .sorted(Comparator.comparingInt(ProviderSourceLineRow::sourceLedgerYear)
                        .thenComparingLong(ProviderSourceLineRow::sourceLineId))
                .map(row -> row.sourceLedgerYear() + ":" + row.sourceLineId()
                        + ":" + row.sourceHash())
                .reduce("", (left, right) -> left + "|" + right);
        return sha256("accounting=" + accounting + "|sourceLines=" + documents);
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
                String.valueOf(row.actionRowId()),
                row.documentType(),
                String.valueOf(row.documentNo()),
                row.resolutionStatus(),
                Boolean.toString(row.cancelled()));
        return sha256(canonical);
    }

    private String providerSourceLineHash(ProviderSourceLineRow line) {
        String canonical = String.join("|",
                line.sourceSystem(),
                Integer.toString(line.sourceLedgerYear()),
                Long.toString(line.sourceCompanyId()),
                Long.toString(line.sourceProjectId()),
                Long.toString(line.sourceDocumentId()),
                Long.toString(line.sourceLineId()),
                Integer.toString(line.lineOrdinal()),
                String.valueOf(line.documentDate()),
                line.documentType(),
                line.documentKind(),
                String.valueOf(line.documentNo()),
                String.valueOf(line.productName()),
                String.valueOf(line.description()),
                String.valueOf(line.quantity()),
                String.valueOf(line.unit()),
                String.valueOf(line.unitPrice()),
                line.netAmount().toPlainString(),
                String.valueOf(line.taxRate()),
                line.taxAmount().toPlainString(),
                line.grossAmount().toPlainString(),
                line.currency(),
                String.valueOf(line.accountCode()),
                Boolean.toString(line.cancelled()));
        return sha256(canonical);
    }

    private String sourceLineFingerprint(List<ProviderSourceLineRow> lines) {
        String canonical = lines.stream()
                .sorted(Comparator.comparingLong(ProviderSourceLineRow::sourceLineId))
                .map(line -> line.sourceLineId() + ":" + line.sourceHash())
                .reduce("", (left, right) -> left + "|" + right);
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

    private ProjectActualRow actualRow(java.sql.ResultSet rs)
            throws java.sql.SQLException {
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
    }

    private ProjectActualSourceLineRow sourceLineRow(java.sql.ResultSet rs)
            throws java.sql.SQLException {
        return new ProjectActualSourceLineRow(
                rs.getObject("id", UUID.class),
                rs.getObject("source_document_id", UUID.class),
                rs.getDate("document_date").toLocalDate(),
                rs.getString("document_type"),
                rs.getString("document_kind"),
                rs.getString("document_no"),
                rs.getLong("external_document_id"),
                rs.getLong("external_line_id"),
                rs.getInt("line_ordinal"),
                rs.getString("product_name"),
                rs.getString("line_description"),
                rs.getBigDecimal("quantity"),
                rs.getString("unit_code"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("net_amount"),
                rs.getBigDecimal("tax_rate"),
                rs.getBigDecimal("tax_amount"),
                rs.getBigDecimal("gross_amount"),
                rs.getBigDecimal("cost_basis_amount"),
                rs.getString("currency"),
                rs.getString("account_code"),
                rs.getString("line_match_status"),
                rs.getString("reconciliation_status"),
                rs.getBigDecimal("accounting_cost_total"),
                rs.getBigDecimal("reconciliation_difference"),
                rs.getInt("accounting_row_count"),
                rs.getBoolean("is_cancelled"),
                rs.getObject("synced_at", OffsetDateTime.class));
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

    private static String sourcePartition(ProviderSourceLineRow row) {
        return "ledger-year:" + row.sourceLedgerYear();
    }

    private static String sourceLineKey(String partition, long sourceLineId) {
        return partition + ":" + sourceLineId;
    }

    private static BigDecimal sourceLineCostBasis(ProviderSourceLineRow line) {
        return switch (line.documentKind()) {
            case "PURCHASE_INVOICE" -> line.netAmount().abs();
            case "PURCHASE_RETURN" -> line.netAmount().abs().negate();
            default -> BigDecimal.ZERO;
        };
    }

    private static boolean isCostDocument(String documentKind) {
        return "PURCHASE_INVOICE".equals(documentKind)
                || "PURCHASE_RETURN".equals(documentKind);
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

    private record SourceDocumentKey(
            String sourcePartition,
            String documentType,
            long externalDocumentId) {
    }

    private record SourceLineMaterialization(
            int documentCount,
            int changedLineCount,
            int tombstoneLineCount,
            int unreconciledDocumentCount) {
    }

    private record ExistingSourceLine(
            UUID id,
            UUID projectBindingId,
            String sourceHash,
            boolean cancelled,
            String lineMatchStatus) {
    }

    private record SourceLineTombstoneCandidate(
            UUID id,
            String sourcePartition,
            long externalLineId,
            String sourceHash,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal grossAmount,
            BigDecimal costBasisAmount,
            String currency,
            String lineMatchStatus) {
    }

    private record SourceDocumentSnapshot(
            UUID id,
            String sourcePartition,
            String documentType,
            long externalDocumentId,
            String documentKind) {
    }

    private record ReconciledSourceLine(
            UUID id,
            long externalLineId,
            String sourceHash,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal grossAmount,
            BigDecimal costBasisAmount,
            String currency,
            String currentStatus,
            boolean cancelled,
            boolean exactMatch) {
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

    private record SourceLineSummary(
            BigDecimal sourceLineActual,
            long sourceDocumentCount,
            long sourceLineCount,
            long unresolvedSourceLineCount) {
    }

    private record SourceDocumentView(
            UUID id,
            LocalDate documentDate,
            String documentType,
            String documentKind,
            String documentNo,
            long externalDocumentId,
            String currency,
            BigDecimal sourceLineTotal,
            BigDecimal accountingCostTotal,
            BigDecimal reconciliationDifference,
            String reconciliationStatus,
            int accountingRowCount,
            boolean cancelled,
            OffsetDateTime syncedAt,
            String sourcePartition) {
    }

    private record Reconciliation(
            String status,
            BigDecimal difference,
            OffsetDateTime executedAt) {
    }
}

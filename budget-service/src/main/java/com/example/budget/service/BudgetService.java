package com.example.budget.service;

import static com.example.budget.api.BudgetDtos.*;

import com.example.budget.domain.VersionStatus;
import com.example.budget.security.BudgetActor;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class BudgetService {
    private final NamedParameterJdbcTemplate namedJdbc;
    private final JdbcTemplate jdbc;
    private final TenantDatabaseScope tenantScope;

    public BudgetService(
            NamedParameterJdbcTemplate namedJdbc,
            JdbcTemplate jdbc,
            TenantDatabaseScope tenantScope) {
        this.namedJdbc = namedJdbc;
        this.jdbc = jdbc;
        this.tenantScope = tenantScope;
    }

    @Transactional
    public BudgetPlanView create(BudgetActor actor, CreateBudgetRequest request) {
        tenantScope.apply(actor.tenantId());
        Integer existing = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_plans WHERE tenant_id=? AND company_id=? AND fiscal_year=?",
                Integer.class, actor.tenantId(), actor.companyId(), request.fiscalYear());
        if (existing != null && existing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget already exists for company and year");
        }

        UUID planId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        try {
            jdbc.update("""
                    INSERT INTO budget_plans
                      (id, tenant_id, company_id, fiscal_year, base_currency, created_by, created_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """, planId, actor.tenantId(), actor.companyId(), request.fiscalYear(),
                    request.baseCurrency(), actor.subject(), now);
        } catch (DataIntegrityViolationException duplicate) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT, "Budget already exists for company and year", duplicate);
        }
        jdbc.update("""
                INSERT INTO budget_versions
                  (id, plan_id, tenant_id, version_no, status, created_by, created_at)
                VALUES (?, ?, ?, 1, 'DRAFT', ?, ?)
                """, versionId, planId, actor.tenantId(), actor.subject(), now);
        audit(actor, "BUDGET_PLAN", planId, "BUDGET_CREATED", request.toString());
        return loadPlan(actor, planId, versionId);
    }

    @Transactional
    public BudgetPlanView replaceLines(
            BudgetActor actor,
            UUID planId,
            UUID versionId,
            ReplaceLinesRequest request) {
        tenantScope.apply(actor.tenantId());
        PlanAccess access = requireAccess(actor, planId, versionId);
        requireDraft(access);

        for (BudgetLineInput line : request.lines()) {
            YearMonth period = parsePeriod(line.period());
            if (period.getYear() != access.fiscalYear()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Line period is outside the fiscal year");
            }
            if (!access.baseCurrency().equals(line.currency())) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Budget line currency must equal the plan base currency until FX planning is enabled");
            }
        }

        jdbc.update("DELETE FROM budget_lines WHERE tenant_id=? AND version_id=?",
                actor.tenantId(), versionId);
        String sql = """
                INSERT INTO budget_lines (
                  id, version_id, tenant_id, period_start, account_code, cost_center_code,
                  project_code, department_code, branch_code, direction, planned_amount,
                  currency, description
                ) VALUES (
                  :id, :versionId, :tenantId, :periodStart, :accountCode, :costCenterCode,
                  :projectCode, :departmentCode, :branchCode, :direction, :plannedAmount,
                  :currency, :description
                )
                """;
        MapSqlParameterSource[] batch = request.lines().stream()
                .map(line -> new MapSqlParameterSource()
                        .addValue("id", UUID.randomUUID())
                        .addValue("versionId", versionId)
                        .addValue("tenantId", actor.tenantId())
                        .addValue("periodStart", Date.valueOf(parsePeriod(line.period()).atDay(1)))
                        .addValue("accountCode", line.accountCode())
                        .addValue("costCenterCode", emptyToNull(line.costCenterCode()))
                        .addValue("projectCode", emptyToNull(line.projectCode()))
                        .addValue("departmentCode", emptyToNull(line.departmentCode()))
                        .addValue("branchCode", emptyToNull(line.branchCode()))
                        .addValue("direction", line.direction())
                        .addValue("plannedAmount", line.plannedAmount())
                        .addValue("currency", line.currency())
                        .addValue("description", emptyToNull(line.description())))
                .toArray(MapSqlParameterSource[]::new);
        namedJdbc.batchUpdate(sql, batch);
        audit(actor, "BUDGET_VERSION", versionId, "BUDGET_LINES_REPLACED",
                "lineCount=" + request.lines().size());
        return loadPlan(actor, planId, versionId);
    }

    @Transactional
    public BudgetPlanView submit(BudgetActor actor, UUID planId, UUID versionId) {
        tenantScope.apply(actor.tenantId());
        PlanAccess access = requireAccess(actor, planId, versionId);
        requireDraft(access);
        Integer lineCount = jdbc.queryForObject(
                "SELECT COUNT(*) FROM budget_lines WHERE tenant_id=? AND version_id=?",
                Integer.class, actor.tenantId(), versionId);
        if (lineCount == null || lineCount == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A budget with no lines cannot be submitted");
        }
        int updated = jdbc.update("""
                UPDATE budget_versions
                   SET status='SUBMITTED', submitted_by=?, submitted_at=?, row_version=row_version+1
                 WHERE id=? AND tenant_id=? AND status='DRAFT'
                """, actor.subject(), OffsetDateTime.now(ZoneOffset.UTC), versionId, actor.tenantId());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget version is no longer a draft");
        }
        audit(actor, "BUDGET_VERSION", versionId, "BUDGET_SUBMITTED", actor.subject());
        return loadPlan(actor, planId, versionId);
    }

    @Transactional
    public BudgetPlanView approve(BudgetActor actor, UUID planId, UUID versionId) {
        tenantScope.apply(actor.tenantId());
        PlanAccess access = requireAccess(actor, planId, versionId);
        if (access.status() != VersionStatus.SUBMITTED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Only a submitted budget can be approved");
        }
        if (actor.subject().equals(access.submittedBy())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Submitter cannot approve the same budget");
        }
        Integer alreadyApproved = jdbc.queryForObject("""
                SELECT COUNT(*) FROM budget_versions
                 WHERE tenant_id=? AND plan_id=? AND status='APPROVED'
                """, Integer.class, actor.tenantId(), planId);
        if (alreadyApproved != null && alreadyApproved > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "An approved version already exists");
        }
        int updated = jdbc.update("""
                UPDATE budget_versions
                   SET status='APPROVED', approved_by=?, approved_at=?, row_version=row_version+1
                 WHERE id=? AND tenant_id=? AND status='SUBMITTED' AND submitted_by<>?
                """, actor.subject(), OffsetDateTime.now(ZoneOffset.UTC),
                versionId, actor.tenantId(), actor.subject());
        if (updated != 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget approval state changed concurrently");
        }
        audit(actor, "BUDGET_VERSION", versionId, "BUDGET_APPROVED", actor.subject());
        return loadPlan(actor, planId, versionId);
    }

    @Transactional(readOnly = true)
    public BudgetPlanView get(BudgetActor actor, UUID planId, UUID versionId) {
        tenantScope.apply(actor.tenantId());
        requireAccess(actor, planId, versionId);
        return loadPlan(actor, planId, versionId);
    }

    /**
     * Latest version of the company's plan for the fiscal year (gitops#3496
     * slice C). Consumers so far had no discovery path — every read required
     * ids only the import response handed out.
     */
    @Transactional(readOnly = true)
    public BudgetPlanView current(BudgetActor actor, int fiscalYear) {
        tenantScope.apply(actor.tenantId());
        record PlanRef(UUID planId, UUID versionId) {
        }
        List<PlanRef> refs = jdbc.query("""
                SELECT p.id AS plan_id, v.id AS version_id
                  FROM budget_plans p
                  JOIN budget_versions v ON v.plan_id=p.id AND v.tenant_id=p.tenant_id
                 WHERE p.tenant_id=? AND p.company_id=? AND p.fiscal_year=?
                 ORDER BY v.version_no DESC
                 LIMIT 1
                """, (rs, rowNum) -> new PlanRef(
                        rs.getObject("plan_id", UUID.class),
                        rs.getObject("version_id", UUID.class)),
                actor.tenantId(), actor.companyId(), fiscalYear);
        if (refs.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND, "No budget plan exists for the fiscal year");
        }
        PlanRef ref = refs.getFirst();
        return loadPlan(actor, ref.planId(), ref.versionId());
    }

    @Transactional(readOnly = true)
    public BudgetControlSummary control(BudgetActor actor, UUID planId, UUID versionId) {
        tenantScope.apply(actor.tenantId());
        PlanAccess access = requireAccess(actor, planId, versionId);
        requireControlCurrencies(actor, access);
        BigDecimal plan = amount("""
                SELECT COALESCE(SUM(planned_amount),0) FROM budget_lines
                 WHERE tenant_id=? AND version_id=? AND direction='EXPENSE'
                """, actor.tenantId(), versionId);
        BigDecimal accountingActual = amount("""
                SELECT COALESCE(SUM(normalized_amount),0) FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND fiscal_year=?
                   AND direction='EXPENSE' AND is_cancelled=FALSE
                """, actor.tenantId(), actor.companyId(), access.fiscalYear());
        BigDecimal allocatedActual = amount("""
                SELECT COALESCE(SUM(a.allocated_amount),0)
                  FROM actual_allocations a
                  JOIN actual_snapshots s ON s.id=a.actual_snapshot_id
                 WHERE a.tenant_id=? AND s.company_id=? AND s.fiscal_year=?
                   AND s.direction='EXPENSE' AND s.is_cancelled=FALSE
                """, actor.tenantId(), actor.companyId(), access.fiscalYear());
        BigDecimal unresolvedActual = amount("""
                SELECT COALESCE(SUM(normalized_amount),0) FROM actual_snapshots
                 WHERE tenant_id=? AND company_id=? AND fiscal_year=?
                   AND direction='EXPENSE' AND is_cancelled=FALSE
                   AND resolution_status IN ('HEADER_ONLY','PARTIAL','UNRESOLVED')
                """, actor.tenantId(), actor.companyId(), access.fiscalYear());
        BigDecimal commitment = amount("""
                SELECT COALESCE(SUM(amount),0) FROM budget_commitments
                 WHERE tenant_id=? AND company_id=? AND fiscal_year=? AND status='OPEN'
                """, actor.tenantId(), actor.companyId(), access.fiscalYear());
        BigDecimal unallocated = accountingActual.subtract(allocatedActual);
        BigDecimal remaining = plan.subtract(accountingActual).subtract(commitment);
        Integer forecastCount = jdbc.queryForObject("""
                SELECT COUNT(*) FROM budget_forecasts WHERE tenant_id=? AND version_id=?
                """, Integer.class, actor.tenantId(), versionId);
        boolean forecastLoaded = forecastCount != null && forecastCount > 0;
        BigDecimal etc = forecastLoaded
                ? amount("SELECT COALESCE(SUM(etc_amount),0) FROM budget_forecasts WHERE tenant_id=? AND version_id=?",
                        actor.tenantId(), versionId)
                : null;
        BigDecimal eac = forecastLoaded ? accountingActual.add(commitment).add(etc) : null;
        BigDecimal variance = forecastLoaded ? plan.subtract(eac) : null;
        return new BudgetControlSummary(
                planId, versionId, actor.companyId(), access.fiscalYear(), access.baseCurrency(),
                access.status(), plan, accountingActual, allocatedActual, unallocated,
                unresolvedActual, commitment, remaining, etc, eac, variance,
                forecastLoaded ? "LOADED" : "NOT_LOADED",
                "All non-cancelled accounting expense rows; unresolved rows remain visible.",
                "Plan - all accounting actual - open commitment; mapping gaps cannot overstate remaining budget.");
    }

    private void requireControlCurrencies(BudgetActor actor, PlanAccess access) {
        Integer incompatible = jdbc.queryForObject("""
                SELECT
                  (SELECT COUNT(*) FROM actual_snapshots
                    WHERE tenant_id=? AND company_id=? AND fiscal_year=? AND is_cancelled=FALSE
                      AND currency<>?)
                  +
                  (SELECT COUNT(*) FROM budget_commitments
                    WHERE tenant_id=? AND company_id=? AND fiscal_year=? AND status='OPEN'
                      AND currency<>?)
                  +
                  (SELECT COUNT(*) FROM budget_forecasts
                    WHERE tenant_id=? AND version_id=? AND currency<>?)
                """, Integer.class,
                actor.tenantId(), actor.companyId(), access.fiscalYear(), access.baseCurrency(),
                actor.tenantId(), actor.companyId(), access.fiscalYear(), access.baseCurrency(),
                actor.tenantId(), access.versionId(), access.baseCurrency());
        if (incompatible != null && incompatible > 0) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Control totals contain non-base-currency rows without an approved FX normalization");
        }
    }

    private BudgetPlanView loadPlan(BudgetActor actor, UUID planId, UUID versionId) {
        PlanAccess access = requireAccess(actor, planId, versionId);
        List<BudgetLineView> lines = jdbc.query("""
                SELECT id, period_start, account_code, cost_center_code, project_code,
                       department_code, branch_code, direction, planned_amount, currency, description
                  FROM budget_lines
                 WHERE tenant_id=? AND version_id=?
                 ORDER BY period_start, account_code, id
                """, (rs, rowNum) -> new BudgetLineView(
                        rs.getObject("id", UUID.class),
                        rs.getDate("period_start").toLocalDate().toString().substring(0, 7),
                        rs.getString("account_code"),
                        rs.getString("cost_center_code"),
                        rs.getString("project_code"),
                        rs.getString("department_code"),
                        rs.getString("branch_code"),
                        rs.getString("direction"),
                        rs.getBigDecimal("planned_amount"),
                        rs.getString("currency"),
                        rs.getString("description")),
                actor.tenantId(), versionId);
        return new BudgetPlanView(
                planId, versionId, actor.companyId(), access.fiscalYear(), access.baseCurrency(),
                access.versionNo(), access.status(), access.submittedBy(), access.approvedBy(), lines);
    }

    private PlanAccess requireAccess(BudgetActor actor, UUID planId, UUID versionId) {
        List<PlanAccess> rows = jdbc.query("""
                SELECT p.fiscal_year, p.base_currency, v.version_no, v.status,
                       v.submitted_by, v.approved_by
                  FROM budget_plans p
                  JOIN budget_versions v ON v.plan_id=p.id AND v.tenant_id=p.tenant_id
                 WHERE p.id=? AND v.id=? AND p.tenant_id=? AND p.company_id=?
                """, (rs, rowNum) -> new PlanAccess(
                        versionId,
                        rs.getInt("fiscal_year"),
                        rs.getString("base_currency"),
                        rs.getInt("version_no"),
                        VersionStatus.valueOf(rs.getString("status")),
                        rs.getString("submitted_by"),
                        rs.getString("approved_by")),
                planId, versionId, actor.tenantId(), actor.companyId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Budget version not found in tenant scope");
        }
        return rows.getFirst();
    }

    private void requireDraft(PlanAccess access) {
        if (access.status() != VersionStatus.DRAFT) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Budget lines are editable only in draft state");
        }
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private void audit(BudgetActor actor, String aggregateType, UUID aggregateId, String eventType, String payload) {
        jdbc.update("""
                INSERT INTO budget_audit_events
                  (id, tenant_id, aggregate_type, aggregate_id, event_type, actor_id, payload_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor.tenantId(), aggregateType, aggregateId, eventType,
                actor.subject(), sha256(payload), OffsetDateTime.now(ZoneOffset.UTC));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private YearMonth parsePeriod(String period) {
        try {
            return YearMonth.parse(period);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid budget period", invalid);
        }
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record PlanAccess(
            UUID versionId,
            int fiscalYear,
            String baseCurrency,
            int versionNo,
            VersionStatus status,
            String submittedBy,
            String approvedBy) {
    }
}

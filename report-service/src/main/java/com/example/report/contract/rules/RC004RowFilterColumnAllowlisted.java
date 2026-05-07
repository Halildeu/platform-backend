package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.BuildTimeSchemaExistenceLookup;
import com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup;
import com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.CoverageStatus;
import com.example.report.contract.schema.TenantColumnAllowlist;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-004 — rowFilter.scopeType=COMPANY column allowlist + schema truth
 * existence cross-check.
 *
 * <p>Phase 2 Program 1d (Codex iter-4 §1d-AGREE absorb): allowlist match
 * established. Phase 2 Program 2c (Codex iter-15 §2c-AGREE absorb):
 * schema truth existence cross-check wired (consumes 2b
 * {@link BuildTimeYearlySchemaCoverageLookup}).
 *
 * <p>Fail modes (4 distinct categories):
 * <ul>
 *   <li>{@code COMPANY rowFilter requires resolvable source table for
 *       allowlist validation; sourceQuery alone is not sufficient}</li>
 *   <li>{@code Column 'X' not in tenant column allowlist for source 'Y'}
 *       (allowlist miss)</li>
 *   <li>{@code SCHEMA_TRUTH_COVERAGE_MISSING: snapshot does not cover
 *       schema/table 'Y'} — 2b coverage artifact incomplete; cannot
 *       authoritatively assert column existence (governance/coverage
 *       problem, not report-definition problem)</li>
 *   <li>{@code Column 'X' not found in schema truth for table 'Y'}
 *       (column absent in covered table — report-definition problem)</li>
 * </ul>
 *
 * <p>Coverage lookup uses the report's first resolvable schema (yearly
 * partition resolution happens at runtime via {@code YearlySchemaResolver};
 * for build-time RC-004 we use the canonical pattern with the report's
 * sourceSchema + the table from def.source()). When the lookup is null,
 * existence check is skipped (1d backward-compat path).
 *
 * <p>Backward-compat constructor (no lookup) skips the existence check;
 * 1d test fixtures keep passing without a full coverage artifact.
 */
public final class RC004RowFilterColumnAllowlisted implements ContractRule {

    private final TenantColumnAllowlist allowlist;
    private final BuildTimeSchemaExistenceLookup existenceLookup;

    public RC004RowFilterColumnAllowlisted(TenantColumnAllowlist allowlist,
                                            BuildTimeSchemaExistenceLookup existenceLookup) {
        this.allowlist = allowlist;
        this.existenceLookup = existenceLookup;
    }

    /**
     * Phase 2 Program 2c iter-18 absorb: legacy 2-arg constructor accepting
     * only the yearly coverage lookup. Wraps it in the unified existence
     * lookup with no canonical fallback (caller-supplied yearly only).
     * Production gate uses the unified 3-arg constructor via
     * {@link com.example.report.contract.ContractValidator#withDefaultRules(TenantColumnAllowlist, BuildTimeSchemaExistenceLookup)}.
     */
    public RC004RowFilterColumnAllowlisted(TenantColumnAllowlist allowlist,
                                            BuildTimeYearlySchemaCoverageLookup yearlyOnly) {
        this(allowlist, yearlyOnly == null
                ? null
                : new BuildTimeSchemaExistenceLookup(null, yearlyOnly));
    }

    /** Backward-compat: allowlist only (1d behavior, no existence check). */
    public RC004RowFilterColumnAllowlisted(TenantColumnAllowlist allowlist) {
        this(allowlist, (BuildTimeSchemaExistenceLookup) null);
    }

    /**
     * No-arg constructor for backward compatibility (1a tests + factory).
     * Behaves like an empty allowlist + no existence check — every
     * legitimate COMPANY rowFilter column will FAIL, so callers must
     * inject the real allowlist for production gate use.
     */
    public RC004RowFilterColumnAllowlisted() {
        this(new TenantColumnAllowlist(java.util.Map.of()), (BuildTimeSchemaExistenceLookup) null);
    }

    @Override
    public String ruleId() {
        return "RC-004";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        AccessConfig access = def.access();
        if (access == null || access.rowFilter() == null) {
            return List.of();
        }
        AccessConfig.RowFilter rowFilter = access.rowFilter();
        if (!"COMPANY".equals(rowFilter.scopeType())) {
            return List.of();
        }
        if (rowFilter.column() == null || rowFilter.column().isBlank()) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "rowFilter.scopeType=COMPANY requires non-blank column"));
        }

        // Codex iter-4 §1d-AGREE: sourceQuery+no source → tek FAIL, allowlist skip.
        // source table çözülemiyorsa allowlist + existence check anlamlı değil.
        if (def.source() == null || def.source().isBlank()) {
            return List.of(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "COMPANY rowFilter requires resolvable source table for "
                            + "allowlist validation; sourceQuery alone is not sufficient"));
        }

        List<ContractViolation> violations = new ArrayList<>();

        // Allowlist match check.
        if (!allowlist.allows(def.source(), rowFilter.column())) {
            violations.add(ContractViolation.fail(
                    ruleId(), def.key(), "rowFilter.column",
                    "Column '" + rowFilter.column() + "' not in tenant column "
                            + "allowlist for source '" + def.source() + "' "
                            + "(scopeType=COMPANY rowFilter must use a tenant boundary column; "
                            + "review allowlist or correct scopeType)"));
        }

        // Phase 2 Program 2c (Codex iter-18 §2c-AGREE absorb, thread 019e0119):
        // schema truth existence cross-check via unified existence lookup
        // (canonical + yearly).
        //
        // Scope: existence check runs only for reports whose tables are
        // expected to be in the governance artifacts:
        //   - yearly schemaMode reports (CARI_ROWS/INVOICE_ROW from 2b yearly artifact)
        //   - canonical-snapshot HR tables (EMPLOYEES_PUANTAJ_ROWS, OFFTIME, etc.)
        // Static-schema tenant reports (workcube_mikrolink_<id>) referencing
        // tables outside both artifact scopes are governance-debt-tracked via
        // RC-004 allowlist + 2e scope migration; we don't double-flag them
        // with SCHEMA_TRUTH_COVERAGE_MISSING.
        //
        // Routing inside BuildTimeSchemaExistenceLookup:
        //   1. Canonical snapshot first (HR/static/global tables)
        //   2. Yearly artifact fallback (CARI_ROWS, INVOICE_ROW, ...)
        //   3. Empty artifact → graceful skip (pre-deployment rollout)
        //
        // NOT_COVERED → SCHEMA_TRUTH_COVERAGE_MISSING (governance/coverage gap)
        // COLUMN_MISSING → RC-004 Column not found (report-definition issue)
        // PRESENT → pass
        boolean existenceCheckApplicable = isYearlyOrCanonicalScope(def);
        if (existenceCheckApplicable && existenceLookup != null && existenceLookup.enforcementActive()) {
            CoverageStatus status = existenceLookup.lookup(
                    def.sourceSchema(), def.source(), rowFilter.column());
            switch (status) {
                case NOT_COVERED -> violations.add(ContractViolation.fail(
                        "SCHEMA_TRUTH_COVERAGE_MISSING", def.key(), "rowFilter.column",
                        "Snapshot does not cover schema/table '" + def.sourceSchema()
                                + "/" + def.source() + "', so RC-004 cannot prove column "
                                + "existence for '" + rowFilter.column() + "' "
                                + "(governance artifact coverage gap; not a report-definition issue)"));
                case COLUMN_MISSING -> violations.add(ContractViolation.fail(
                        ruleId(), def.key(), "rowFilter.column",
                        "Column '" + rowFilter.column() + "' not found in schema truth "
                                + "for table '" + def.source() + "' in schema '"
                                + def.sourceSchema() + "' (snapshot covers the table but "
                                + "lacks this column; report definition references a "
                                + "nonexistent column)"));
                case PRESENT -> { /* OK */ }
            }
        }

        return violations;
    }

    /**
     * Phase 2 Program 2c iter-18 §2c absorb: existence check applicability
     * gate. Returns true only when the report's source table is expected to
     * be in either the canonical snapshot (HR/global tables) or the yearly
     * artifact (yearly partitions).
     *
     * <p>Static-schema tenant reports (workcube_mikrolink_&lt;id&gt;) referencing
     * tables outside both artifact scopes (e.g. ORDERS, ORDER_ROW in
     * legacy reports) are NOT subject to existence enforcement here; they
     * are governance-debt-tracked via RC-004 allowlist + 2e scope migration.
     */
    private static boolean isYearlyOrCanonicalScope(ReportDefinition def) {
        if (def == null) {
            return false;
        }
        // Yearly partitioning: definitely covered by 2b artifact contract
        if ("yearly".equals(def.schemaMode())) {
            return true;
        }
        // Static reports pointing to canonical workcube_mikrolink schema:
        // HR/global tables expected in canonical snapshot.
        if (def.sourceSchema() != null
                && def.sourceSchema().equalsIgnoreCase("workcube_mikrolink")) {
            return true;
        }
        return false;
    }
}

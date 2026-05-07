package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.example.report.contract.schema.TenantColumnAllowlist;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 Program 1d — RC-004 row filter column allowlist tests.
 *
 * <p>Codex iter-4 §1d-AGREE absorb: allowlist-only check (existence cross-check
 * deferred to Phase 2 Program 2). Two distinct fail modes covered:
 * <ul>
 *   <li>Allowlist miss → "Column not in tenant column allowlist for source"</li>
 *   <li>Source unresolved (sourceQuery + no source) → single fail, skip rest</li>
 * </ul>
 */
class RC004RowFilterColumnAllowlistedTest {

    private static final TenantColumnAllowlist ALLOWLIST = new TenantColumnAllowlist(Map.of(
            "INVOICE", List.of("COMPANY_ID"),
            "CARI_ROWS", List.of("FROM_CMP_ID", "OUR_COMPANY_ID")));

    @Test
    void validate_companyRowFilter_columnInAllowlist_returnsEmpty() {
        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_companyRowFilter_columnNotInAllowlist_failsWithAllowlistMessage() {
        ReportDefinition def = newDef("INVOICE", "DEPARTMENT_ID");  // not allowlisted

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).severity()).isEqualTo(ContractViolation.Severity.FAIL);
        assertThat(violations.get(0).message())
                .contains("not in tenant column allowlist for source 'INVOICE'");
    }

    @Test
    void validate_companyRowFilter_unknownSourceTable_failsWithAllowlistMessage() {
        ReportDefinition def = newDef("UNKNOWN_TABLE", "ANY_COL");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("not in tenant column allowlist for source 'UNKNOWN_TABLE'"));
    }

    @Test
    void validate_companyRowFilter_sourceQueryNoSource_singleFailFastReturn() {
        // sourceQuery + no source: single fail, skip allowlist check.
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                null,  // source absent
                "workcube_mikrolink_1", "static", null,
                "SELECT * FROM {schema}.SOMETHING WHERE COMPANY_ID = ?",
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("COMPANY_ID", "COMPANY", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).message())
                .contains("COMPANY rowFilter requires resolvable source table");
    }

    @Test
    void validate_nonCompanyScope_returnsEmpty_skipsAllowlistEntirely() {
        // BRANCH scopeType not subject to RC-004 allowlist.
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "EXPENSE_ITEM_PLANS", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("BRANCH_ID", "B", "number", 100, false)),
                "BRANCH_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("BRANCH_ID", "BRANCH", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_noRowFilter_returnsEmpty() {
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "INVOICE", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                null);  // no access

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_blankColumn_failsWithRequiredColumnMessage() {
        ReportDefinition def = new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                "INVOICE", "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition("COMPANY_ID", "C", "number", 100, false)),
                "COMPANY_ID", "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter("", "COMPANY", null)));

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted(ALLOWLIST).validate(def);

        assertThat(violations).anyMatch(v ->
                "RC-004".equals(v.ruleId())
                        && v.message().contains("requires non-blank column"));
    }

    @Test
    void validate_emptyAllowlistDefault_failsAllCompanyRowFilters() {
        // Backward-compat: no-arg constructor uses empty allowlist; everything
        // fails. Production callers must inject real allowlist via overload.
        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");

        List<ContractViolation> violations = new RC004RowFilterColumnAllowlisted().validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).message())
                .contains("not in tenant column allowlist");
    }

    // Phase 2 Program 2c (Codex iter-15 §2c-AGREE absorb): existence cross-check tests

    @Test
    void validate_coverageLookup_columnPresent_passesExistenceCheck() {
        // Allowlist matches, coverage PRESENT → no violation.
        // Use yearly schemaMode so existence check is applicable.
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "COMPANY_ID"))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.PRESENT);

        ReportDefinition def = newYearlyDef("INVOICE", "COMPANY_ID", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).isEmpty();
    }

    @Test
    void validate_coverageLookup_columnMissing_failsWithRC004() {
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "COMPANY_ID"))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.COLUMN_MISSING);

        ReportDefinition def = newYearlyDef("INVOICE", "COMPANY_ID", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("RC-004");
        assertThat(violations.get(0).message())
                .contains("not found in schema truth")
                .contains("INVOICE");
    }

    @Test
    void validate_coverageLookup_notCovered_failsWithSchemaTruthCoverageMissing() {
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup("workcube_mikrolink_2026_1", "INVOICE", "COMPANY_ID"))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.NOT_COVERED);

        ReportDefinition def = newYearlyDef("INVOICE", "COMPANY_ID", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).hasSize(1);
        assertThat(violations.get(0).ruleId()).isEqualTo("SCHEMA_TRUTH_COVERAGE_MISSING");
        assertThat(violations.get(0).message())
                .contains("Snapshot does not cover")
                .contains("governance artifact coverage gap");
    }

    @Test
    void validate_emptyCoverageLookup_skipsExistenceCheck_gracefulDegradation() {
        // Pre-2b deployment: coverage artifact not yet present. Lookup returns
        // schemaCount=0 → existence check skipped. Allowlist match still
        // applies (no false-positive SCHEMA_TRUTH_COVERAGE_MISSING).
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup emptyLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(emptyLookup.schemaCount()).thenReturn(0);

        ReportDefinition def = newDef("INVOICE", "COMPANY_ID");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, emptyLookup).validate(def);

        assertThat(violations).isEmpty();  // allowlist OK, existence check skipped
    }

    @Test
    void validate_combinedAllowlistMissAndColumnMissing_emitsBothFailures() {
        // Allowlist miss + COLUMN_MISSING — both surface (orthogonal failure modes).
        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup mockLookup =
                org.mockito.Mockito.mock(
                        com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup.class);
        org.mockito.Mockito.when(mockLookup.schemaCount()).thenReturn(3);
        org.mockito.Mockito.when(mockLookup.lookup(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(com.example.report.contract.schema.BuildTimeYearlySchemaCoverageLookup
                        .CoverageStatus.COLUMN_MISSING);

        // INVOICE allowlists COMPANY_ID only; UNKNOWN_COL is not in allowlist
        // → produces both allowlist miss + column missing.
        // Use yearly mode so existence check is applicable.
        ReportDefinition def = newYearlyDef("INVOICE", "UNKNOWN_COL", "workcube_mikrolink_2026_1");
        List<ContractViolation> violations =
                new RC004RowFilterColumnAllowlisted(ALLOWLIST, mockLookup).validate(def);

        assertThat(violations).hasSize(2);
        assertThat(violations).extracting("ruleId")
                .containsExactly("RC-004", "RC-004");
        assertThat(violations).extracting("message")
                .anyMatch(m -> m.toString().contains("not in tenant column allowlist"))
                .anyMatch(m -> m.toString().contains("not found in schema truth"));
    }

    private ReportDefinition newDef(String source, String rowFilterColumn) {
        // Static schema (workcube_mikrolink_1) — existence check NOT applicable.
        // Used for allowlist-only test scenarios.
        return new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                source, "workcube_mikrolink_1", "static", null, null,
                List.of(new ColumnDefinition(rowFilterColumn, "C", "number", 100, false)),
                rowFilterColumn, "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter(rowFilterColumn, "COMPANY", null)));
    }

    private ReportDefinition newYearlyDef(String source, String rowFilterColumn, String sourceSchema) {
        // Yearly schema — existence check applicable (Phase 2 Program 2c).
        return new ReportDefinition(
                "test-rep", "1.0", "Test", null, "Finans",
                source, sourceSchema, "yearly", "ACTION_DATE", null,
                List.of(new ColumnDefinition(rowFilterColumn, "C", "number", 100, false)),
                rowFilterColumn, "ASC",
                new AccessConfig("perm", null, null,
                        new AccessConfig.RowFilter(rowFilterColumn, "COMPANY", null)));
    }
}

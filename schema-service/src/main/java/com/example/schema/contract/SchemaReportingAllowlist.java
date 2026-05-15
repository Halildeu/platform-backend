package com.example.schema.contract;

import java.util.Locale;
import java.util.Set;

/**
 * Adım 12 — schema-service-side mirror of the Workcube reporting
 * source-table allowlist (V1 pre-SEAL snapshot).
 *
 * <p>Codex {@code 019e2d64} S1: the canonical {@code ReportingAllowlist.V1}
 * lives in <b>report-service</b>
 * ({@code report-service/.../contract/schema/ReportingAllowlist.java}).
 * schema-service must NOT take a build/runtime dependency on
 * report-service ({@code schema-service/pom.xml} is standalone and stays
 * that way). So this is an intentional in-repo mirror, kept byte-identical
 * to the report-service V1 set; drift is caught by the
 * {@link #V1} count assertion in {@code SchemaReportingAllowlistTest}.
 *
 * <p><b>Why mirror, not share</b>: a shared {@code reporting-contracts}
 * Java module is the cleaner long-term answer, but it expands the CI /
 * dependency graph well beyond the Adım 12 PR-4 prerequisite scope.
 * Codex flagged that as a follow-up, not a blocker. Until the shared
 * module lands, the two 40-table sets are kept in lockstep by review
 * discipline + the count test.
 *
 * <p><b>Composition</b> (40 tables, matching report-service V1):
 * 23 ADR-0012-SS canonical + 7 existing-report {@code source} entries
 * + 10 Adım 11.2a sourceQuery JOIN-target entries.
 *
 * <p><b>Uppercase discipline</b>: MSSQL identifier comparison is
 * case-insensitive but the canonical inventory uses uppercase. Callers
 * normalise to uppercase via {@link #containsV1(String)} before
 * consulting the set.
 */
public final class SchemaReportingAllowlist {

    private SchemaReportingAllowlist() {
    }

    /** Allowlist name surfaced in the reporting-contract response. */
    public static final String NAME = "ReportingAllowlist";

    /** Allowlist version surfaced in the reporting-contract response. */
    public static final String VERSION = "V1";

    /**
     * Pre-SEAL V1 allowlist — 40 tables. Mirror of
     * {@code report-service ReportingAllowlist.V1}. {@code V2} succeeds
     * this set after Faz 16.1 annex 2A SEAL.
     */
    public static final Set<String> V1 = Set.of(
            // ADR-0012-SS §2.2 23 canonical
            "INVOICE",
            "INVOICE_ROW",
            "CARI_ROWS",
            "CARI_ACTIONS",
            "BANK_ACTIONS",
            "CASH_ACTIONS",
            "CHEQUE",
            "COMPANY_REMAINDER",
            "ORDERS",
            "ORDER_ROW",
            "OUR_COMPANY",
            "BRANCH",
            "DEPARTMENT",
            "PRO_PROJECTS",
            "STOCK_FIS",
            "STOCK_FIS_ROW",
            "ACCOUNT_CARD",
            "ACCOUNT_CARD_ROWS",
            "ACCOUNT_CARD_MONEY",
            "EXPENSE_ITEM_PLANS",
            "SETUP_PROCESS_CAT",
            "EMPLOYEE_POSITIONS",
            "EMPLOYEES_SALARY",
            // Existing report `source` regression-safety (7)
            "BUDGET_PLAN_ROW",
            "EMPLOYEES_IN_OUT",
            "EMPLOYEES_PUANTAJ_ROWS",
            "EMPLOYEES_SALARY_HISTORY",
            "EMPLOYEE_DAILY_IN_OUT",
            "OFFTIME",
            "TRAINING_CLASS_ATTENDER",
            // Adım 11.2a inventory sweep — sourceQuery JOIN targets (10)
            "ACCOUNT_PLAN",
            "COMPANY",
            "CONSUMER",
            "EMPLOYEES",
            "EMPLOYEES_DETAIL",
            "EMPLOYEES_IDENTY",
            "EMPLOYEES_PUANTAJ",
            "EXPENSE_ITEMS",
            "MONEY_HISTORY",
            "SETUP_DOCUMENT_TYPE"
    );

    /**
     * Returns whether the given table name is in the V1 allowlist
     * (case-insensitive).
     */
    public static boolean containsV1(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        return V1.contains(tableName.toUpperCase(Locale.ROOT));
    }
}

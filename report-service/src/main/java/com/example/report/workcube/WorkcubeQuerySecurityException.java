package com.example.report.workcube;

/**
 * Phase 2 Program 11.2b — runtime security violation in
 * {@link WorkcubeQueryAdapter} (Adım 11.2b).
 *
 * <p>Thrown when a rendered SQL surfaces a table the
 * {@link com.example.report.contract.schema.ReportingAllowlist#V1} does
 * not cover, or an unsupported / unqualified target the static
 * {@link com.example.report.contract.schema.WorkcubeSqlTableRefScanner}
 * classifies fail-closed. Build-time {@code RC-011} already prevents
 * most of these from reaching production; this exception is the
 * second-line defence at execution time after template render +
 * placeholder substitution.
 *
 * <p>Mapped by {@link WorkcubeQueryExceptionHandler} to a 403
 * {@code workcube_query_security_violation} HTTP body.
 */
public class WorkcubeQuerySecurityException extends RuntimeException {

    private final String reportKey;

    public WorkcubeQuerySecurityException(String reportKey, String message) {
        super(message);
        this.reportKey = reportKey;
    }

    public String reportKey() {
        return reportKey;
    }
}

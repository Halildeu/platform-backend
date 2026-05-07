package com.example.report.query;

/**
 * Phase 2 Program 2a — Tenant selection required for yearly-partitioned report.
 *
 * <p>Codex iter-10 §2a-AGREE absorb (thread 019e0119): yearly schemaMode reports
 * require an explicit COMPANY scope (or super-admin + explicit X-Company-Id
 * picker header). Silent {@code def.sourceSchema()} fallback removed; this
 * exception triggers a deterministic 400 {@code tenant_selection_required}.
 */
public class TenantSelectionRequiredException extends RuntimeException {

    private final String reportKey;

    public TenantSelectionRequiredException(String reportKey, String message) {
        super(message);
        this.reportKey = reportKey;
    }

    public String reportKey() {
        return reportKey;
    }
}

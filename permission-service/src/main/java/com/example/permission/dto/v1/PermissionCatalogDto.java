package com.example.permission.dto.v1;

import java.util.List;

public record PermissionCatalogDto(
        List<ModuleCatalogItem> modules,
        List<ActionCatalogItem> actions,
        List<ReportCatalogItem> reports
) {
    public record ModuleCatalogItem(String key, String label, List<String> levels) {}
    public record ActionCatalogItem(String key, String label, String module, boolean deniable) {}

    /**
     * Codex 019dda1c iter-26: report catalog item — typed dashboard granule.
     *
     * <p>{@code key} — upper-snake permission key persisted in
     * {@code role_permissions.permission_key} (e.g. {@code HR_ANALYTICS}).
     * The same identity flows into OpenFGA tuples and shows up in
     * {@code authz/me} as both {@code reports.hr-analytics.view} and
     * {@code dashboards.hr-analytics.view} aliases.
     *
     * <p>{@code label} — Turkish display name shown in the role drawer
     * (e.g. "İK Analitik Dashboard"). Mirrors the source dashboard JSON's
     * {@code title} field.
     *
     * <p>{@code module} — coarse module gate the report belongs to. For
     * dashboard reports this is {@code "REPORT"}; legacy free-form
     * reports may carry a different module key for backward compatibility.
     *
     * <p>{@code category} — UI grouping label shown in drawer accordion
     * headers (e.g. "İnsan Kaynakları" / "Finans"). Mirrors the source
     * dashboard JSON's {@code category} field. When null, drawer falls
     * back to {@code module} for grouping (legacy compatibility).
     */
    public record ReportCatalogItem(String key, String label, String module, String category) {
        /** Backward-compat constructor — defaults category to module name. */
        public ReportCatalogItem(String key, String label, String module) {
            this(key, label, module, module);
        }
    }
}

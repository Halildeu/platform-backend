package com.example.permission.dto.v1;

import java.util.List;

public record PermissionCatalogDto(
        List<ModuleCatalogItem> modules,
        List<ActionCatalogItem> actions,
        List<ReportCatalogItem> reports,
        List<ReportGroupCatalogItem> reportGroups
) {
    /**
     * R16 PR-D full (Codex 019e2a5d/019e2a83 absorb) — backward-compat
     * constructor; reportGroups null → empty list.
     */
    public PermissionCatalogDto(
            List<ModuleCatalogItem> modules,
            List<ActionCatalogItem> actions,
            List<ReportCatalogItem> reports
    ) {
        this(modules, actions, reports, List.of());
    }

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

    /**
     * R16 PR-D full (Codex 019e2a5d/019e2a83 absorb) — report_group catalog item.
     *
     * <p>FE rol permission UI "Rapor Yetki Grupları" alt-bölümünde render edilir.
     * {@code reports.<GROUP>} permission key'leri dashboard granule'ları (HR_*,
     * FIN_*) ile aynı listede karışmaz — ayrı section.
     *
     * <p>{@code key} — backend permission key (örn. {@code reports.FINANCE_REPORTS}).
     * RoleDrawer save payload'da bu key olduğu gibi gider; backend
     * TupleSyncService key-aware mapping ile OpenFGA {@code report_group:<KEY>}
     * tuple'a çevirir.
     *
     * <p>{@code objectId} — OpenFGA object instance id (örn. {@code FINANCE_REPORTS}).
     * {@code key.substring("reports.".length())} eşittir.
     *
     * <p>{@code label} — Turkish display name (örn. "Finans Raporları").
     */
    public record ReportGroupCatalogItem(
            String key,
            String objectId,
            String label,
            String description) {}
}

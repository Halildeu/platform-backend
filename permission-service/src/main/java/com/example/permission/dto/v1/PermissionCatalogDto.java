package com.example.permission.dto.v1;

import java.util.List;

public record PermissionCatalogDto(
        List<ModuleCatalogItem> modules,
        List<ActionCatalogItem> actions,
        List<ReportCatalogItem> reports,
        List<PageCatalogItem> pages
) {
    public record ModuleCatalogItem(String key, String label, List<String> levels) {}
    public record ActionCatalogItem(String key, String label, String module, boolean deniable) {}
    public record ReportCatalogItem(String key, String label, String module) {}
    public record PageCatalogItem(String key, String label, String module) {}
}

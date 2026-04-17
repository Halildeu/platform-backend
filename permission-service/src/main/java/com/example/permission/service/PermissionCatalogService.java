package com.example.permission.service;

import com.example.permission.dto.v1.PermissionCatalogDto;
import com.example.permission.dto.v1.PermissionCatalogDto.ModuleCatalogItem;
import com.example.permission.dto.v1.PermissionCatalogDto.ActionCatalogItem;
import com.example.permission.dto.v1.PermissionCatalogDto.ReportCatalogItem;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Provides the permission catalog — all available permission granules.
 * Replaces hardcoded MODULE_KEYS across the codebase.
 * Initially code-defined; can be migrated to DB-driven later.
 */
@Service
public class PermissionCatalogService {

    private static final List<ModuleCatalogItem> MODULES = List.of(
            new ModuleCatalogItem("USER_MANAGEMENT", "Kullanıcı Yönetimi", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("ACCESS", "Erişim Yönetimi", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("AUDIT", "Denetim", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("REPORT", "Raporlama", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("WAREHOUSE", "Depo", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("PURCHASE", "Satın Alma", List.of("VIEW", "MANAGE")),
            new ModuleCatalogItem("THEME", "Tema", List.of("VIEW", "MANAGE"))
    );

    private static final List<ActionCatalogItem> ACTIONS = List.of(
            new ActionCatalogItem("APPROVE_PURCHASE", "Satın Alma Onay", "PURCHASE", true),
            new ActionCatalogItem("CREATE_PO", "Sipariş Oluştur", "PURCHASE", true),
            new ActionCatalogItem("DELETE_PO", "Sipariş Sil", "PURCHASE", true),
            new ActionCatalogItem("RESET_PASSWORD", "Parola Sıfırla", "USER_MANAGEMENT", true),
            new ActionCatalogItem("DELETE_USER", "Kullanıcı Sil", "USER_MANAGEMENT", true),
            new ActionCatalogItem("TOGGLE_STATUS", "Durum Değiştir", "USER_MANAGEMENT", true)
    );

    // P1-B: Report groups (CNS-20260410-001 consensus)
    private static final List<ReportCatalogItem> REPORTS = List.of(
            new ReportCatalogItem("HR_REPORTS", "İK Raporları", "USER_MANAGEMENT"),
            new ReportCatalogItem("FINANCE_REPORTS", "Finans Raporları", "REPORT"),
            new ReportCatalogItem("SALES_REPORTS", "Satış Raporları", "PURCHASE"),
            new ReportCatalogItem("ANALYTICS_REPORTS", "Analitik Dashboardlar", "REPORT"),
            new ReportCatalogItem("PURCHASE_SUMMARY", "Satın Alma Özeti", "PURCHASE"),
            new ReportCatalogItem("WAREHOUSE_STOCK", "Stok Raporu", "WAREHOUSE")
    );

    public PermissionCatalogDto getCatalog() {
        return new PermissionCatalogDto(MODULES, ACTIONS, REPORTS);
    }

    public List<String> getModuleKeys() {
        return MODULES.stream().map(ModuleCatalogItem::key).toList();
    }

    /**
     * Returns the Turkish user-facing label for a canonical module key
     * (matches /v1/authz/catalog seed). Used by AccessRoleService to
     * produce /v1/roles policies[].moduleLabel from a canonical key.
     */
    public Optional<String> getModuleLabel(String moduleKey) {
        if (moduleKey == null) return Optional.empty();
        return MODULES.stream()
                .filter(m -> m.key().equals(moduleKey))
                .map(ModuleCatalogItem::label)
                .findFirst();
    }
}

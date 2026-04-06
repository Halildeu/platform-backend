package com.example.permission.service;

import com.example.permission.dto.v1.PermissionCatalogDto;
import com.example.permission.dto.v1.PermissionCatalogDto.ModuleCatalogItem;
import com.example.permission.dto.v1.PermissionCatalogDto.ActionCatalogItem;
import com.example.permission.dto.v1.PermissionCatalogDto.ReportCatalogItem;
import com.example.permission.dto.v1.PermissionCatalogDto.PageCatalogItem;
import org.springframework.stereotype.Service;

import java.util.List;

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

    private static final List<ReportCatalogItem> REPORTS = List.of(
            new ReportCatalogItem("PURCHASE_SUMMARY", "Satın Alma Özeti", "PURCHASE"),
            new ReportCatalogItem("HR_COMPENSATION", "İK Maaş Raporu", "USER_MANAGEMENT"),
            new ReportCatalogItem("WAREHOUSE_STOCK", "Stok Raporu", "WAREHOUSE")
    );

    private static final List<PageCatalogItem> PAGES = List.of(
            new PageCatalogItem("ADMIN_SETTINGS", "Yönetici Ayarları", "ACCESS"),
            new PageCatalogItem("PURCHASE_SETTINGS", "Satın Alma Ayarları", "PURCHASE")
    );

    public PermissionCatalogDto getCatalog() {
        return new PermissionCatalogDto(MODULES, ACTIONS, REPORTS, PAGES);
    }

    public List<String> getModuleKeys() {
        return MODULES.stream().map(ModuleCatalogItem::key).toList();
    }
}

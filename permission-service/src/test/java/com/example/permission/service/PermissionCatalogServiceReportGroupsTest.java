package com.example.permission.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.permission.dto.v1.PermissionCatalogDto;
import com.example.permission.dto.v1.PermissionCatalogDto.ReportGroupCatalogItem;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * R16 PR-D full (Codex 019e2a5d/019e2a83 absorb) — PermissionCatalogService
 * reportGroups field extension test.
 *
 * <p>RoleDrawer FE save whitelist'i için catalog.reportGroups field zorunlu.
 * Bu test PR-D0 hotfix (string prefix preserve) ile uyumlu — backend
 * catalog'da `reports.<GROUP>` formatında listeler.
 *
 * <p>R16 PR-B-2 PermissionDataInitializer.DEFAULT_REPORT_GROUP_KEYS ile
 * senkron olmalı (4 group: FINANCE_REPORTS, HR_REPORTS, SALES_REPORTS,
 * ANALYTICS_REPORTS).
 */
class PermissionCatalogServiceReportGroupsTest {

    private final PermissionCatalogService service = new PermissionCatalogService();

    @Test
    void getCatalog_includesReportGroups() {
        PermissionCatalogDto catalog = service.getCatalog();
        assertThat(catalog.reportGroups()).isNotNull().hasSize(4);
    }

    @Test
    void reportGroups_keysHaveReportsPrefix() {
        PermissionCatalogDto catalog = service.getCatalog();
        for (ReportGroupCatalogItem g : catalog.reportGroups()) {
            assertThat(g.key()).startsWith("reports.");
        }
    }

    @Test
    void reportGroups_objectIdEqualsSuffix() {
        // FE rol permission save için key kullanır (reports.FINANCE_REPORTS);
        // OpenFGA tuple object_id için objectId kullanır (FINANCE_REPORTS).
        PermissionCatalogDto catalog = service.getCatalog();
        for (ReportGroupCatalogItem g : catalog.reportGroups()) {
            String expected = g.key().substring("reports.".length());
            assertThat(g.objectId()).isEqualTo(expected);
        }
    }

    @Test
    void reportGroups_setMatches_REPORT_GROUP_KEYS_contract() {
        // R16 PR-B-2 TupleSyncService.REPORT_GROUP_KEYS senkron check.
        // Yeni reportGroup eklenirse her iki yer güncellenmeli.
        PermissionCatalogDto catalog = service.getCatalog();
        Set<String> catalogObjectIds = catalog.reportGroups().stream()
                .map(ReportGroupCatalogItem::objectId)
                .collect(java.util.stream.Collectors.toSet());
        assertThat(catalogObjectIds)
                .containsExactlyInAnyOrder(
                        "FINANCE_REPORTS",
                        "HR_REPORTS",
                        "SALES_REPORTS",
                        "ANALYTICS_REPORTS");
    }

    @Test
    void reportGroups_haveTurkishLabels() {
        PermissionCatalogDto catalog = service.getCatalog();
        // Codex 019e2a5d naming önerisi: Türkçe display labels
        assertThat(catalog.reportGroups()).extracting(ReportGroupCatalogItem::label)
                .containsExactlyInAnyOrder(
                        "Finans Raporları",
                        "İnsan Kaynakları Raporları",
                        "Satış Raporları",
                        "Analitik Raporlar");
    }

    @Test
    void backwardCompatConstructor_emptyReportGroups() {
        // 3-arg constructor (legacy) reportGroups=List.of()
        PermissionCatalogDto dto = new PermissionCatalogDto(List.of(), List.of(), List.of());
        assertThat(dto.reportGroups()).isEmpty();
    }
}

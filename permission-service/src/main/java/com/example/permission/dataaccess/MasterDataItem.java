package com.example.permission.dataaccess;

/**
 * Lightweight DTO for master data scope picker dropdowns.
 *
 * <p>2026-04-29: Workcube ERP master data (our_company, pro_projects, branch,
 * department) için minimal shape. Frontend ScopeAssignModal text input → dropdown
 * geçişi için kullanılır. Tek seferlik populate fetch (filter/search frontend
 * client-side), pagination yok (typical master data ≤ 1000 row).
 *
 * @param id     workcube source PK (numeric, e.g. comp_id, project_id, branch_id)
 * @param name   display name (Turkish locale, fallback empty string)
 * @param status active/inactive flag (true = aktif)
 */
public record MasterDataItem(Long id, String name, boolean status) {
}

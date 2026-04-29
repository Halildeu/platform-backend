package com.example.permission.service;

import java.util.Map;

/**
 * Legacy {@code permissions.module_name} value canonicalizer.
 *
 * <p>Background (Codex 019dd818 iter-11/12 PARTIAL → Plan A+):
 * The {@code permissions} table has historical module_name values written in
 * Turkish UI labels ("Kullanıcı Yönetimi") or lowercase legacy keys ("reporting")
 * instead of canonical module keys ("USER_MANAGEMENT", "REPORT"). Live diagnostic
 * (2026-04-29) revealed that {@link AccessRoleService#toDto(Role)} groups
 * {@code role.rolePermissions} by these mangled labels, producing
 * {@code byModule['Sistem Yönetimi']} keys that the frontend catalog cannot
 * match — every role drawer renders all module slots as "—" (NONE).
 *
 * <p>Mapping table (canonical key set: USER_MANAGEMENT, ACCESS, AUDIT, REPORT,
 * WAREHOUSE, PURCHASE, THEME from {@link PermissionCatalogService#MODULES}):
 * <ul>
 *   <li>{@code Kullanıcı Yönetimi} → {@code USER_MANAGEMENT}</li>
 *   <li>{@code Access} → {@code ACCESS}</li>
 *   <li>{@code Sistem Yönetimi} → {@code ACCESS} (role/permission/scope mgmt)</li>
 *   <li>{@code Audit} → {@code AUDIT}</li>
 *   <li>{@code reporting}, {@code Raporlama} → {@code REPORT}</li>
 *   <li>{@code Depo} → {@code WAREHOUSE}</li>
 *   <li>{@code Satın Alma} → {@code PURCHASE}</li>
 *   <li>{@code Tema Yönetimi} → {@code THEME}</li>
 * </ul>
 *
 * <p>Out-of-scope (P2 follow-up — Codex iter-11):
 * <ul>
 *   <li>{@code Variant}: separate canonical module needs catalog expansion decision</li>
 *   <li>{@code scope}: cross-cutting bypass action, not a module — refactor to
 *       PermissionType.ACTION needed</li>
 *   <li>{@code Company}: separate canonical module needs catalog expansion</li>
 * </ul>
 *
 * <p>This helper is paired with a Flyway V27 migration that normalizes the DB
 * row content; the runtime canonicalizer ensures forward-compatibility against
 * future drift (e.g. test fixtures, manual SQL inserts).
 */
public final class ModuleNameCanonicalizer {

    private static final Map<String, String> LEGACY_LABEL_TO_CANONICAL = Map.ofEntries(
            Map.entry("Kullanıcı Yönetimi", "USER_MANAGEMENT"),
            Map.entry("Access", "ACCESS"),
            Map.entry("Sistem Yönetimi", "ACCESS"),
            Map.entry("Audit", "AUDIT"),
            Map.entry("reporting", "REPORT"),
            Map.entry("Raporlama", "REPORT"),
            Map.entry("Depo", "WAREHOUSE"),
            Map.entry("Satın Alma", "PURCHASE"),
            Map.entry("Tema Yönetimi", "THEME")
    );

    private ModuleNameCanonicalizer() {
        // utility — not instantiable
    }

    /**
     * Returns the canonical module key for a legacy {@code permissions.module_name}
     * value, or the original input if no mapping exists.
     *
     * <p>Pass-through behavior is intentional: unknown module names (e.g.
     * {@code Variant}, {@code Company}, {@code scope}) flow through unchanged,
     * letting {@link AccessRoleService#deriveModuleIdentity(java.util.List, String)}
     * emit its WARN log so future drift remains observable.
     *
     * @param moduleName legacy {@code permissions.module_name} value (may be null/blank)
     * @return canonical module key when mapping is known; original value otherwise;
     *         {@code "GENERIC"} when input is null
     */
    public static String canonicalize(String moduleName) {
        if (moduleName == null) {
            return "GENERIC";
        }
        return LEGACY_LABEL_TO_CANONICAL.getOrDefault(moduleName, moduleName);
    }
}

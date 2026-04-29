package com.example.permission.service;

import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionType;
import com.example.permission.model.RolePermission;

import java.util.Locale;
import java.util.Optional;

/**
 * Maps legacy flat permission codes onto STORY-0318 Zanzibar granules.
 * This keeps older seed/update flows compatible with the non-null
 * permission_type / permission_key / grant_type schema.
 */
public final class RolePermissionGranuleDefaults {

    private RolePermissionGranuleDefaults() {
    }

    public static void apply(RolePermission rolePermission, Permission permission) {
        rolePermission.setPermission(permission);
        if (permission == null) {
            // Granule-only source: caller must populate permissionType/permissionKey/grantType
            // directly (see AccessRoleService.cloneRole granule path). No legacy code → granule
            // resolution needed, and resolve(null) would NPE.
            return;
        }
        Granule granule = resolve(permission);
        rolePermission.setPermissionType(granule.permissionType());
        rolePermission.setPermissionKey(granule.permissionKey());
        rolePermission.setGrantType(granule.grantType());
    }

    /**
     * Returns the canonical MODULE key for a permission (e.g. "REPORT", "PURCHASE"),
     * derived from the permission's code via {@link #resolve(Permission)}.
     * Returns {@link Optional#empty()} when the permission resolves to ACTION/REPORT/PAGE
     * (non-MODULE granule type) — callers can fall back to label-derived keys for
     * those cases, but MUST NOT use label normalization for canonical modules.
     * <p>
     * Non-MODULE parent module derivation (e.g. {@code reports.HR_REPORTS} →
     * {@code USER_MANAGEMENT}, {@code APPROVE_PURCHASE} → {@code PURCHASE}) is
     * intentionally out of scope: {@link PermissionCatalogService#REPORTS} contains
     * per-report parent assignments that a simple prefix rule cannot reproduce
     * (HR_REPORTS lives under USER_MANAGEMENT, SALES_REPORTS under PURCHASE). Handle
     * catalog-driven parent lookup in a future iteration if {@code /v1/roles}
     * policies summary needs to group action/report granules under their parent.
     * <p>
     * Locale-independent: output is the same regardless of JVM default locale
     * (unlike legacy label normalization which produced "RAPORLAMA" from Turkish
     * label "Raporlama" in tr_TR JVM).
     */
    public static Optional<String> canonicalModuleKey(Permission permission) {
        if (permission == null) return Optional.empty();
        Granule granule = resolve(permission);
        return granule.permissionType() == PermissionType.MODULE
                ? Optional.of(granule.permissionKey())
                : Optional.empty();
    }

    private static Granule resolve(Permission permission) {
        String code = permission.getCode();
        String normalizedCode = code == null ? "" : code.trim();
        String upperCode = normalizedCode.toUpperCase(Locale.ROOT);

        // Codex 019dda1c iter-26: legacy permission codes use kebab-case
        // slugs (e.g. "reports.hr-analytics.view"), but the catalog and DB
        // permission_key column use upper-snake (HR_ANALYTICS). Strip the
        // ".view" suffix and convert kebab → upper-snake so the granule key
        // matches PermissionCatalogService.REPORTS canonical identity.
        if (normalizedCode.startsWith("reports.")) {
            String tail = normalizedCode.substring("reports.".length());
            boolean isView = tail.endsWith(".view");
            String slug = isView ? tail.substring(0, tail.length() - ".view".length()) : tail;
            return new Granule(
                    PermissionType.REPORT,
                    slugToUpperSnake(slug),
                    isView ? GrantType.VIEW : GrantType.ALLOW
            );
        }
        // P1-A: dashboards.* no longer mapped to PAGE (removed type)
        // Codex 019dda1c iter-26: dashboards.* alias of reports.* — same
        // canonical upper-snake key. Pre-iter-26 the suffix was passed through
        // verbatim ("hr-analytics"); now normalized to HR_ANALYTICS.
        if (normalizedCode.startsWith("dashboards.")) {
            String tail = normalizedCode.substring("dashboards.".length());
            boolean isView = tail.endsWith(".view");
            String slug = isView ? tail.substring(0, tail.length() - ".view".length()) : tail;
            return new Granule(
                    PermissionType.REPORT,
                    slugToUpperSnake(slug),
                    isView ? GrantType.VIEW : GrantType.ALLOW
            );
        }
        if (upperCode.equals("VIEW_USERS") || upperCode.equals("MANAGE_USERS") || normalizedCode.startsWith("user-")) {
            return module("USER_MANAGEMENT", upperCode);
        }
        if (normalizedCode.startsWith("access-")) {
            return module("ACCESS", upperCode);
        }
        if (normalizedCode.startsWith("audit-")) {
            return module("AUDIT", upperCode);
        }
        if (upperCode.equals("VIEW_PURCHASE") || upperCode.equals("APPROVE_PURCHASE")) {
            return module("PURCHASE", upperCode);
        }
        if (upperCode.equals("VIEW_WAREHOUSE") || upperCode.equals("MANAGE_WAREHOUSE")) {
            return module("WAREHOUSE", upperCode);
        }
        if (upperCode.startsWith("REPORT_")) {
            return module("REPORT", upperCode);
        }
        if (upperCode.equals("THEME_ADMIN")) {
            return new Granule(PermissionType.MODULE, "THEME", GrantType.MANAGE);
        }
        if (normalizedCode.startsWith("company-")) {
            return module("COMPANY", upperCode);
        }
        if (normalizedCode.equals("role-manage")
                || normalizedCode.equals("permission-manage")
                || normalizedCode.equals("permission-scope-manage")
                || normalizedCode.equals("system-configure")) {
            return new Granule(PermissionType.ACTION, normalizedCode, GrantType.ALLOW);
        }

        String moduleName = permission.getModuleName();
        if (moduleName != null && !moduleName.isBlank()) {
            return module(normalizeModuleKey(moduleName), upperCode);
        }

        return new Granule(PermissionType.ACTION, normalizedCode, GrantType.ALLOW);
    }

    private static Granule module(String key, String upperCode) {
        return new Granule(
                PermissionType.MODULE,
                key,
                isManageLike(upperCode) ? GrantType.MANAGE : GrantType.VIEW
        );
    }

    private static boolean isManageLike(String upperCode) {
        return upperCode.contains("MANAGE")
                || upperCode.contains("WRITE")
                || upperCode.contains("CREATE")
                || upperCode.contains("DELETE")
                || upperCode.contains("UPDATE")
                || upperCode.contains("APPROVE")
                || upperCode.contains("EXPORT")
                || upperCode.contains("IMPORT")
                || upperCode.contains("ADMIN")
                || upperCode.contains("CONFIGURE");
    }

    private static String normalizeModuleKey(String moduleLabel) {
        return moduleLabel
                .toUpperCase(Locale.ROOT)
                .replace('İ', 'I')
                .replace('I', 'I')
                .replace('Ş', 'S')
                .replace('Ğ', 'G')
                .replace('Ü', 'U')
                .replace('Ö', 'O')
                .replace('Ç', 'C')
                .replaceAll("[^A-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
    }

    /**
     * Codex 019dda1c iter-26: convert a kebab-case dashboard slug
     * ({@code "hr-analytics"}, {@code "fin-ratios"}) into the canonical
     * upper-snake permission key persisted in {@code role_permissions.
     * permission_key} ({@code "HR_ANALYTICS"}, {@code "FIN_RATIOS"}).
     *
     * <p>Used by both the {@code reports.*} and {@code dashboards.*} legacy
     * permission code branches above. Locale.ROOT prevents the tr_TR
     * dotted-I issue on JVMs with Turkish locale.
     */
    private static String slugToUpperSnake(String slug) {
        return slug.toUpperCase(Locale.ROOT).replace('-', '_').replace('.', '_');
    }

    private record Granule(PermissionType permissionType, String permissionKey, GrantType grantType) {
    }
}

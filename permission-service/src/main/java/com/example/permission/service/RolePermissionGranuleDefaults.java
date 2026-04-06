package com.example.permission.service;

import com.example.permission.model.GrantType;
import com.example.permission.model.Permission;
import com.example.permission.model.PermissionType;
import com.example.permission.model.RolePermission;

import java.util.Locale;

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
        Granule granule = resolve(permission);
        rolePermission.setPermissionType(granule.permissionType());
        rolePermission.setPermissionKey(granule.permissionKey());
        rolePermission.setGrantType(granule.grantType());
    }

    private static Granule resolve(Permission permission) {
        String code = permission.getCode();
        String normalizedCode = code == null ? "" : code.trim();
        String upperCode = normalizedCode.toUpperCase(Locale.ROOT);

        if (normalizedCode.startsWith("reports.")) {
            return new Granule(
                    PermissionType.REPORT,
                    normalizedCode.substring("reports.".length()),
                    normalizedCode.endsWith(".view") ? GrantType.VIEW : GrantType.ALLOW
            );
        }
        if (normalizedCode.startsWith("dashboards.")) {
            return new Granule(
                    PermissionType.PAGE,
                    normalizedCode.substring("dashboards.".length()),
                    GrantType.ALLOW
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

    private record Granule(PermissionType permissionType, String permissionKey, GrantType grantType) {
    }
}

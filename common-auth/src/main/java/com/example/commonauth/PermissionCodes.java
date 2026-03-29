package com.example.commonauth;

/**
 * Yetki kodlarinin tekil kaynagi.
 *
 * OpenFGA gecisi sonrasi, yetki kontrolleri module bazli yapilacak:
 *   authzService.check(userId, "can_view", "module", "USER_MANAGEMENT")
 *
 * Bu dosyadaki sabitler gecis doneminde backward compatibility icin korunur.
 * Yeni kod OpenFGA relation isimleri kullanmali.
 *
 * OpenFGA relation mapping:
 *   can_view   = read access (eski: *_READ, VIEW_*)
 *   can_manage = write/admin access (eski: *_WRITE, *_MANAGE, MANAGE_*)
 *   admin      = full control (eski: ADMIN, *_ADMIN)
 */
public final class PermissionCodes {
    private PermissionCodes() {
    }

    // =========================================================================
    // OpenFGA Module Constants (CANONICAL — use these in new code)
    // =========================================================================
    public static final String MODULE_USER_MANAGEMENT = "USER_MANAGEMENT";
    public static final String MODULE_ACCESS = "ACCESS";
    public static final String MODULE_AUDIT = "AUDIT";
    public static final String MODULE_REPORT = "REPORT";
    public static final String MODULE_WAREHOUSE = "WAREHOUSE";
    public static final String MODULE_PURCHASE = "PURCHASE";
    public static final String MODULE_THEME = "THEME";
    public static final String MODULE_COMPANY = "COMPANY";

    // OpenFGA relations
    public static final String RELATION_CAN_VIEW = "can_view";
    public static final String RELATION_CAN_MANAGE = "can_manage";
    public static final String RELATION_ADMIN = "admin";
    public static final String RELATION_VIEWER = "viewer";
    public static final String RELATION_MANAGER = "manager";
    public static final String RELATION_EDITOR = "editor";
    public static final String RELATION_OPERATOR = "operator";

    // =========================================================================
    // Legacy constants (DEPRECATED — will be removed after full OpenFGA migration)
    // =========================================================================

    // Variant service
    @Deprecated(forRemoval = true) public static final String MANAGE_GLOBAL_VARIANTS = "MANAGE_GLOBAL_VARIANTS";
    @Deprecated(forRemoval = true) public static final String VARIANTS_READ = "VARIANTS_READ";
    @Deprecated(forRemoval = true) public static final String VARIANTS_WRITE = "VARIANTS_WRITE";

    // Access management
    @Deprecated(forRemoval = true) public static final String ACCESS_READ = "access-read";
    @Deprecated(forRemoval = true) public static final String ACCESS_CREATE = "access-create";
    @Deprecated(forRemoval = true) public static final String ACCESS_UPDATE = "access-update";
    @Deprecated(forRemoval = true) public static final String ACCESS_DELETE = "access-delete";

    // Core data
    @Deprecated(forRemoval = true) public static final String COMPANY_READ = "company-read";
    @Deprecated(forRemoval = true) public static final String COMPANY_WRITE = "company-write";

    // Audit
    @Deprecated(forRemoval = true) public static final String AUDIT_READ = "audit-read";
    @Deprecated(forRemoval = true) public static final String AUDIT_CREATE = "audit-create";
    @Deprecated(forRemoval = true) public static final String AUDIT_UPDATE = "audit-update";
    @Deprecated(forRemoval = true) public static final String AUDIT_DELETE = "audit-delete";

    // Governance
    @Deprecated(forRemoval = true) public static final String ROLE_MANAGE = "role-manage";
    @Deprecated(forRemoval = true) public static final String PERMISSION_MANAGE = "permission-manage";
    @Deprecated(forRemoval = true) public static final String PERMISSION_SCOPE_MANAGE = "permission-scope-manage";
    @Deprecated(forRemoval = true) public static final String SYSTEM_CONFIGURE = "system-configure";
    @Deprecated(forRemoval = true) public static final String THEME_ADMIN = "THEME_ADMIN";

    // User management
    @Deprecated(forRemoval = true) public static final String USER_READ = "user-read";
    @Deprecated(forRemoval = true) public static final String USER_CREATE = "user-create";
    @Deprecated(forRemoval = true) public static final String USER_UPDATE = "user-update";
    @Deprecated(forRemoval = true) public static final String USER_DELETE = "user-delete";
    @Deprecated(forRemoval = true) public static final String USER_EXPORT = "user-export";
    @Deprecated(forRemoval = true) public static final String USER_IMPORT = "user-import";

    // Reporting
    @Deprecated(forRemoval = true) public static final String REPORT_VIEW = "REPORT_VIEW";
    @Deprecated(forRemoval = true) public static final String REPORT_EXPORT = "REPORT_EXPORT";
    @Deprecated(forRemoval = true) public static final String REPORT_MANAGE = "REPORT_MANAGE";
}

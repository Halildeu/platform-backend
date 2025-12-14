package com.example.commonauth;

/**
 * Yetki kodlarının tekil kaynağı. Keycloak mapper'ı ve backend servisleri bu sabitlerle hizalanmalı.
 */
public final class PermissionCodes {
    private PermissionCodes() {
    }

    public static final String MANAGE_GLOBAL_VARIANTS = "MANAGE_GLOBAL_VARIANTS";
    public static final String VARIANTS_READ = "VARIANTS_READ";
    public static final String VARIANTS_WRITE = "VARIANTS_WRITE";

    // Access management service
    public static final String ACCESS_READ = "access-read";
    public static final String ACCESS_CREATE = "access-create";
    public static final String ACCESS_UPDATE = "access-update";
    public static final String ACCESS_DELETE = "access-delete";

    // Core data / company master
    public static final String COMPANY_READ = "company-read";
    public static final String COMPANY_WRITE = "company-write";

    // Audit service
    public static final String AUDIT_READ = "audit-read";
    public static final String AUDIT_CREATE = "audit-create";
    public static final String AUDIT_UPDATE = "audit-update";
    public static final String AUDIT_DELETE = "audit-delete";

    // System / governance
    public static final String ROLE_MANAGE = "role-manage";
    public static final String PERMISSION_MANAGE = "permission-manage";
    public static final String PERMISSION_SCOPE_MANAGE = "permission-scope-manage";
    public static final String SYSTEM_CONFIGURE = "system-configure";
    public static final String THEME_ADMIN = "THEME_ADMIN";

    // User management
    public static final String USER_READ = "user-read";
    public static final String USER_CREATE = "user-create";
    public static final String USER_UPDATE = "user-update";
    public static final String USER_DELETE = "user-delete";
    public static final String USER_EXPORT = "user-export";
    public static final String USER_IMPORT = "user-import";
}

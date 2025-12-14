package com.example.user.permission;

public final class PermissionActions {

    private PermissionActions() {
    }

    // Export / import operations
    public static final String USER_EXPORT = "user-export";
    public static final String USER_IMPORT = "user-import";

    // Granular user operations (lowercase codes aligned with permission-service)
    public static final String USER_READ = "user-read";
    public static final String USER_CREATE = "user-create";
    public static final String USER_UPDATE = "user-update";
    public static final String USER_DELETE = "user-delete";
}

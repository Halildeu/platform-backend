package com.example.permission.service;

import java.util.Set;

/**
 * Central policy for sensitive module visibility.
 *
 * <p>Keys in {@link #EXPLICIT_GRANT_ONLY_MODULES} may be present in the public
 * module catalog, but a catalog entry or an {@code ADMIN} role name must never
 * create access by itself. They require an explicit module granule/permission.
 */
public final class PermissionModulePolicy {

    private static final Set<String> EXPLICIT_GRANT_ONLY_MODULES =
            Set.of("INTERVIEW_EVIDENCE", "ATS");

    private PermissionModulePolicy() {
    }

    public static boolean allowsImplicitAdminExpansion(String moduleKey) {
        return moduleKey != null && !EXPLICIT_GRANT_ONLY_MODULES.contains(moduleKey);
    }
}

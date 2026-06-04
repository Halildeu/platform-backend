package com.example.permission.service;

import java.util.Locale;
import java.util.Map;

/**
 * Single source of truth for the LEGACY permission-code → OpenFGA module-tuple
 * mapping (Faz pre-granule permission-registry model).
 *
 * <p>Extracted 2026-06-04 (AG-028 revoke-orphan fix, platform-k8s-gitops #1272,
 * Codex Option B). Before this, the maps lived only in
 * {@link PermissionService} and drove the legacy WRITE path
 * ({@code syncTuplesToOpenFga}). The granule-aware feature reconciliation in
 * {@link TupleSyncService} must now know the legacy desired tuple set so it can
 * <em>spare</em> legacy tuples from its ground-truth delete (otherwise a refresh
 * for a user holding both a legacy and a granule role would wipe the legacy
 * {@code module:*} tuples it never re-writes). Sharing one mapper keeps the
 * write path and the spare-set computation from drifting.
 */
public final class LegacyPermissionTupleMapper {

    private LegacyPermissionTupleMapper() {
    }

    /** Legacy permission code → OpenFGA {@code module} object id. */
    public static final Map<String, String> PERM_TO_MODULE = Map.ofEntries(
            Map.entry("VIEW_USERS", "USER_MANAGEMENT"),
            Map.entry("MANAGE_USERS", "USER_MANAGEMENT"),
            Map.entry("VIEW_ACCESS", "ACCESS"),
            Map.entry("MANAGE_ACCESS", "ACCESS"),
            Map.entry("VIEW_AUDIT", "AUDIT"),
            Map.entry("VIEW_PURCHASE", "PURCHASE"),
            Map.entry("APPROVE_PURCHASE", "PURCHASE"),
            Map.entry("VIEW_WAREHOUSE", "WAREHOUSE"),
            Map.entry("MANAGE_WAREHOUSE", "WAREHOUSE"),
            Map.entry("VIEW_REPORT", "REPORT"),
            Map.entry("MANAGE_REPORT", "REPORT")
    );

    /** Legacy permission code → OpenFGA {@code module} relation. */
    public static final Map<String, String> PERM_TO_RELATION = Map.ofEntries(
            Map.entry("VIEW_USERS", "can_view"),
            Map.entry("MANAGE_USERS", "can_manage"),
            Map.entry("VIEW_ACCESS", "can_view"),
            Map.entry("MANAGE_ACCESS", "can_manage"),
            Map.entry("VIEW_AUDIT", "can_view"),
            Map.entry("VIEW_PURCHASE", "can_view"),
            Map.entry("APPROVE_PURCHASE", "can_manage"),
            Map.entry("VIEW_WAREHOUSE", "can_view"),
            Map.entry("MANAGE_WAREHOUSE", "can_manage"),
            Map.entry("VIEW_REPORT", "can_view"),
            Map.entry("MANAGE_REPORT", "can_manage")
    );

    /** A legacy module tuple ({@code module:<objectId>#<relation>}). */
    public record LegacyTuple(String relation, String objectType, String objectId) {
        /** Canonical {@code relation|objectType:objectId} identity used for spare-set comparison. */
        public String canonical() {
            return relation + "|" + objectType + ":" + objectId;
        }
    }

    /**
     * Map a legacy permission code (case-insensitive) to its OpenFGA module
     * tuple, or {@code null} if the code is not a legacy-mapped permission.
     */
    public static LegacyTuple toTuple(String permissionCode) {
        if (permissionCode == null) return null;
        String code = permissionCode.toUpperCase(Locale.ROOT);
        String module = PERM_TO_MODULE.get(code);
        String relation = PERM_TO_RELATION.get(code);
        if (module == null || relation == null) return null;
        return new LegacyTuple(relation, "module", module);
    }
}

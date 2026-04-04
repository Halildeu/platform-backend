package com.example.user.permission;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Permission check via OpenFGA (Zanzibar).
 *
 * Previously called permission-service HTTP API.
 * Now uses OpenFgaAuthzService.check() directly — no network hop.
 */
@Component
public class PermissionServiceClient {

    private static final Logger log = LoggerFactory.getLogger(PermissionServiceClient.class);

    private final OpenFgaAuthzService authzService;

    public PermissionServiceClient(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
    }

    /**
     * Check if user has permission.
     *
     * Maps legacy permission actions to OpenFGA relations:
     *   VIEW_*  → can_view on module
     *   MANAGE_* / EDIT_* / APPROVE_* → can_manage on module
     *
     * Company/project/warehouse scope is handled by ScopeContextFilter separately.
     */
    public boolean hasPermission(Long userId,
                                 Long companyId,
                                 Long projectId,
                                 Long warehouseId,
                                 String action) {
        if (action == null || action.isBlank()) {
            return false;
        }

        String upperAction = action.toUpperCase();
        String module = resolveModule(upperAction);
        String relation = resolveRelation(upperAction);

        if (module == null) {
            log.debug("Unknown permission action: {} — denying", action);
            return false;
        }

        boolean allowed = authzService.check(
                String.valueOf(userId),
                relation,
                "module",
                module
        );

        log.debug("OpenFGA check: user:{} {} module:{} → {}", userId, relation, module, allowed);
        return allowed;
    }

    private String resolveModule(String action) {
        if (action.contains("USER")) return "USER_MANAGEMENT";
        if (action.contains("ACCESS")) return "ACCESS";
        if (action.contains("AUDIT")) return "AUDIT";
        if (action.contains("PURCHASE")) return "PURCHASE";
        if (action.contains("WAREHOUSE")) return "WAREHOUSE";
        if (action.contains("REPORT")) return "REPORT";
        if (action.contains("THEME")) return "THEME";
        return null;
    }

    private String resolveRelation(String action) {
        if (action.startsWith("VIEW_")) return "can_view";
        if (action.startsWith("MANAGE_") || action.startsWith("EDIT_") || action.startsWith("APPROVE_")) return "can_manage";
        // admin-level actions
        if (action.equals("ADMIN") || action.contains("RESET_PASSWORD") || action.contains("TOGGLE_STATUS")) return "can_manage";
        return "can_view";
    }
}

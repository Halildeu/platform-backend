package com.example.user.controller;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.ScopeContext;
import com.example.commonauth.scope.ScopeContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Authorization proxy endpoints for frontend consumption.
 * Wraps OpenFGA checks behind a REST API so frontend never
 * talks to OpenFGA directly.
 *
 * In dev/permitAll mode, returns dev scope from YAML config.
 */
@RestController
@RequestMapping("/api/v1/authz")
public class AuthzProxyControllerV1 {

    private final OpenFgaAuthzService authzService;

    public AuthzProxyControllerV1(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
    }

    /**
     * Get current user's permissions and scopes.
     * Frontend calls this on login/token refresh.
     */
    @GetMapping("/me")
    public ResponseEntity<AuthzMeResponse> getMyAuthorization() {
        ScopeContext ctx = ScopeContextHolder.get();

        String userId = ctx != null ? ctx.userId() : "anonymous";
        boolean superAdmin = ctx != null && ctx.superAdmin();
        Set<Long> companyIds = ctx != null ? ctx.allowedCompanyIds() : Set.of();
        Set<Long> projectIds = ctx != null ? ctx.allowedProjectIds() : Set.of();
        Set<Long> warehouseIds = ctx != null ? ctx.allowedWarehouseIds() : Set.of();

        // Module-level permissions
        List<String> modules = List.of(
                "USER_MANAGEMENT", "ACCESS", "AUDIT", "REPORT",
                "WAREHOUSE", "PURCHASE", "THEME"
        );

        var modulePermissions = modules.stream()
                .filter(m -> superAdmin || authzService.check(userId, "can_view", "module", m))
                .toList();

        return ResponseEntity.ok(new AuthzMeResponse(
                userId,
                superAdmin,
                modulePermissions,
                companyIds,
                projectIds,
                warehouseIds
        ));
    }

    /**
     * Point authorization check.
     * Frontend calls this for specific UI element visibility.
     */
    @PostMapping("/check")
    public ResponseEntity<CheckResponse> check(@RequestBody CheckRequest request) {
        ScopeContext ctx = ScopeContextHolder.get();
        String userId = ctx != null ? ctx.userId() : "anonymous";

        boolean allowed = ctx != null && ctx.superAdmin()
                || authzService.check(userId, request.relation(), request.objectType(), request.objectId());

        return ResponseEntity.ok(new CheckResponse(allowed));
    }

    // --- DTOs ---

    public record AuthzMeResponse(
            String userId,
            boolean superAdmin,
            List<String> allowedModules,
            Set<Long> allowedCompanyIds,
            Set<Long> allowedProjectIds,
            Set<Long> allowedWarehouseIds
    ) {}

    public record CheckRequest(
            String relation,
            String objectType,
            String objectId
    ) {}

    public record CheckResponse(boolean allowed) {}
}

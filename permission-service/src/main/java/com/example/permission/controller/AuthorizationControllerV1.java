package com.example.permission.controller;

import com.example.permission.dto.v1.AuthzUserScopesResponseDto;
import com.example.permission.dto.v1.AuthzMeResponseDto;
import com.example.permission.dto.v1.AuthzScopeSummaryDto;
import com.example.permission.dto.v1.ExplainResponseDto;
import com.example.permission.dto.v1.PermissionCatalogDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.dto.v1.UserAssignmentRequestDto;
import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.RolePermission;
import com.example.permission.model.UserRoleAssignment;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthorizationQueryService;
import com.example.permission.service.PermissionCatalogService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/authz")
public class AuthorizationControllerV1 {

    private static final Logger log = LoggerFactory.getLogger(AuthorizationControllerV1.class);
    private static final CacheControl SCOPES_CACHE_CONTROL = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final AuthorizationQueryService authorizationQueryService;
    private final AuthenticatedUserLookupService authenticatedUserLookupService;
    private final PermissionService permissionService;
    private final PermissionCatalogService catalogService;
    private final TupleSyncService tupleSyncService;
    private final OpenFgaAuthzService authzService;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final RolePermissionRepository rolePermissionRepository;

    public AuthorizationControllerV1(
            AuthorizationQueryService authorizationQueryService,
            AuthenticatedUserLookupService authenticatedUserLookupService,
            PermissionService permissionService,
            PermissionCatalogService catalogService,
            TupleSyncService tupleSyncService,
            OpenFgaAuthzService authzService,
            UserRoleAssignmentRepository assignmentRepository,
            RolePermissionRepository rolePermissionRepository
    ) {
        this.authorizationQueryService = authorizationQueryService;
        this.authenticatedUserLookupService = authenticatedUserLookupService;
        this.permissionService = permissionService;
        this.catalogService = catalogService;
        this.tupleSyncService = tupleSyncService;
        this.authzService = authzService;
        this.assignmentRepository = assignmentRepository;
        this.rolePermissionRepository = rolePermissionRepository;
    }

    @GetMapping("/user/{userId}/scopes")
    public ResponseEntity<AuthzUserScopesResponseDto> getUserScopes(@PathVariable Long userId) {
        AuthzUserScopesResponseDto response = authorizationQueryService.getUserScopes(userId);
        if (response.getItems() == null || response.getItems().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AUTHZ-404");
        }
        return ResponseEntity.ok()
                .cacheControl(SCOPES_CACHE_CONTROL)
                .body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthzMeResponseDto> getMe(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT missing");
        }
        try {
            AuthzMeResponseDto dto = new AuthzMeResponseDto();
            var resolvedUser = resolveAuthenticatedUser(jwt);
            dto.setUserId(resolvedUser.responseUserId());

            Long numericUserId = resolvedUser.numericUserId();

            // Legacy permissions (backward compat)
            dto.setPermissions(resolvePermissionsSafely(jwt, numericUserId));
            // SuperAdmin: check OpenFGA organization admin first, then fall back to permissions list
            boolean isSuperAdmin = checkOrganizationAdmin(numericUserId)
                    || dto.getPermissions().stream()
                            .anyMatch(p -> p != null && p.equalsIgnoreCase("admin"));
            dto.setSuperAdmin(isSuperAdmin);

            // Scopes (existing)
            Map<String, Set<Long>> scopeSummary = resolveScopeSummarySafely(numericUserId);
            List<AuthzScopeSummaryDto> scopes = scopeSummary.entrySet().stream()
                    .map(e -> new AuthzScopeSummaryDto(e.getKey(), e.getValue().stream().toList()))
                    .collect(Collectors.toList());
            dto.setScopes(scopes);
            List<ScopeSummaryDto> allowedScopes = scopes.stream()
                    .flatMap(s -> s.getRefIds().stream().map(id -> new ScopeSummaryDto(s.getScopeType(), id)))
                    .toList();
            dto.setAllowedScopes(allowedScopes);

            // STORY-0318: Enhanced response fields
            if (numericUserId != null) {
                populateEnhancedFieldsSafely(dto, numericUserId);
            }

            applyFrontendCompatibilityFallback(dto, jwt);
            return ResponseEntity.ok(dto);
        } catch (RuntimeException ex) {
            log.error("Authz /me beklenmeyen hata ile sonuçlandı; JWT fallback response döndürülecek. cause={}", ex.getMessage(), ex);
            return ResponseEntity.ok(buildJwtFallbackResponse(jwt));
        }
    }

    /**
     * Permission catalog — all available permission granules.
     * Replaces hardcoded module lists.
     */
    @GetMapping("/catalog")
    public ResponseEntity<PermissionCatalogDto> getCatalog() {
        return ResponseEntity.ok(catalogService.getCatalog());
    }

    /**
     * List all module keys. Replaces hardcoded module lists in frontend.
     */
    @GetMapping("/modules")
    public ResponseEntity<List<String>> getModules() {
        return ResponseEntity.ok(catalogService.getModuleKeys());
    }

    /**
     * Get roles assigned to a user.
     */
    @GetMapping("/users/{userId}/roles")
    public ResponseEntity<List<Map<String, Object>>> getUserRoles(@PathVariable Long userId) {
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(userId);
        List<Map<String, Object>> roles = assignments.stream()
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("roleId", a.getRole().getId());
                    m.put("roleName", a.getRole().getName());
                    m.put("assignedAt", a.getAssignedAt() != null ? a.getAssignedAt().toString() : null);
                    return m;
                })
                .toList();
        return ResponseEntity.ok(roles);
    }

    /**
     * Assign roles + scopes to a user.
     */
    @PostMapping("/users/{userId}/assignments")
    public ResponseEntity<Map<String, Object>> assignUserRolesAndScopes(
            @PathVariable Long userId,
            @RequestBody UserAssignmentRequestDto request) {
        // Deactivate existing assignments
        List<UserRoleAssignment> existing = assignmentRepository.findActiveAssignments(userId);
        for (UserRoleAssignment assignment : existing) {
            assignment.setActive(false);
            assignmentRepository.save(assignment);
        }

        // Create new assignments for each role
        List<Long> roleIds = request.roleIds() != null ? request.roleIds() : List.of();
        for (Long roleId : roleIds) {
            var assignRequest = new com.example.permission.dto.PermissionAssignRequest();
            assignRequest.setUserId(userId);
            assignRequest.setRoleId(roleId);
            permissionService.assignRole(assignRequest);
        }

        // Sync scope tuples
        if (request.scopes() != null) {
            var scopes = request.scopes();
            tupleSyncService.syncScopeTuples(
                    String.valueOf(userId),
                    scopes.companyIds(),
                    scopes.projectIds(),
                    scopes.warehouseIds(),
                    scopes.branchIds()
            );
        }

        // Refresh feature tuples (union of all roles, deny-wins)
        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        tupleSyncService.syncFeatureTuplesForUser(String.valueOf(userId), allPermissions);

        return ResponseEntity.ok(Map.of(
                "userId", userId,
                "roleIds", roleIds,
                "status", "assigned"
        ));
    }

    /**
     * Explain why a user can or cannot perform a specific permission.
     */
    @PostMapping("/explain")
    public ResponseEntity<ExplainResponseDto> explain(@RequestBody Map<String, String> request) {
        String userIdStr = request.get("userId");
        String permTypeStr = request.get("permissionType");
        String permKey = request.get("permissionKey");
        // Optional scope check: "can user access company:35?"
        String scopeType = request.get("scopeType");     // company, project, warehouse, branch
        String scopeRefId = request.get("scopeRefId");    // e.g. "35"

        if (userIdStr == null || permTypeStr == null || permKey == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId, permissionType, permissionKey required");
        }

        Long userId = Long.parseLong(userIdStr);
        PermissionType permType;
        try {
            permType = PermissionType.valueOf(permTypeStr.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid permissionType: " + permTypeStr);
        }

        Map<String, List<Long>> userScopes = buildScopeMap(userId);

        // Scope-level denial check (if requested)
        if (scopeType != null && scopeRefId != null) {
            List<Long> scopeIds = userScopes.getOrDefault(scopeType, List.of());
            Long refId = Long.parseLong(scopeRefId);
            if (!scopeIds.contains(refId)) {
                List<String> userRoles = resolveUserRoleNames(userId);
                return ResponseEntity.ok(ExplainResponseDto.denied(
                        "NO_SCOPE", scopeType, scopeRefId, null, null, userRoles, userScopes));
            }
        }

        // Get user's roles
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(userId);
        List<String> userRoles = assignments.stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();

        if (assignments.isEmpty()) {
            return ResponseEntity.ok(ExplainResponseDto.denied(
                    "NO_ROLE", permTypeStr, permKey, null, null, userRoles, userScopes));
        }

        // Get all permissions from user's roles
        List<Long> roleIds = assignments.stream()
                .map(a -> a.getRole().getId())
                .distinct()
                .toList();
        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);

        // Resolve effective grants
        Map<String, TupleSyncService.ResolvedGrant> effective = tupleSyncService.resolveEffectiveGrants(allPermissions);
        String compositeKey = permType.name() + ":" + permKey;
        TupleSyncService.ResolvedGrant grant = effective.get(compositeKey);

        if (grant == null) {
            return ResponseEntity.ok(ExplainResponseDto.denied(
                    "NO_PERMISSION", permTypeStr, permKey, null, null, userRoles, userScopes));
        }

        if (grant.grantType() == GrantType.DENY) {
            return ResponseEntity.ok(ExplainResponseDto.denied(
                    "DENIED_BY_ROLE", permTypeStr, permKey,
                    grant.sourceRole(), "DENY", userRoles, userScopes));
        }

        return ResponseEntity.ok(ExplainResponseDto.allowed(
                permTypeStr, permKey, grant.sourceRole(),
                grant.grantType().name(), userRoles, userScopes));
    }

    private List<String> resolveUserRoleNames(Long userId) {
        return assignmentRepository.findActiveAssignments(userId).stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();
    }

    // --- Private helpers ---

    private void populateEnhancedFields(AuthzMeResponseDto dto, Long numericUserId) {
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(numericUserId);
        List<String> roleNames = assignments.stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();
        dto.setRoles(roleNames);

        List<Long> roleIds = assignments.stream()
                .map(a -> a.getRole().getId())
                .distinct()
                .toList();

        if (roleIds.isEmpty()) return;

        List<RolePermission> allPermissions = rolePermissionRepository.findByRoleIdIn(roleIds);
        Map<String, TupleSyncService.ResolvedGrant> effective = tupleSyncService.resolveEffectiveGrants(allPermissions);

        Map<String, String> modules = new LinkedHashMap<>();
        Map<String, String> actions = new LinkedHashMap<>();
        Map<String, String> reports = new LinkedHashMap<>();
        Map<String, String> pages = new LinkedHashMap<>();

        for (var entry : effective.entrySet()) {
            String[] parts = entry.getKey().split(":", 2);
            PermissionType type = PermissionType.valueOf(parts[0]);
            String key = parts[1];
            String grantStr = entry.getValue().grantType().name();

            switch (type) {
                case MODULE -> modules.put(key, grantStr);
                case ACTION -> actions.put(key, grantStr);
                case REPORT -> reports.put(key, grantStr);
                case PAGE -> pages.put(key, grantStr);
                case FIELD -> {} // fields not included in /me for now
            }
        }

        dto.setModules(modules);
        dto.setActions(actions);
        dto.setReports(reports);
        dto.setPages(pages);
    }

    private Map<String, List<Long>> buildScopeMap(Long userId) {
        Map<String, Set<Long>> scopeSummary = authorizationQueryService.getUserScopeSummary(userId);
        Map<String, List<Long>> result = new LinkedHashMap<>();
        scopeSummary.forEach((k, v) -> result.put(k, new ArrayList<>(v)));
        return result;
    }

    private Set<String> resolvePermissions(Jwt jwt, Long numericUserId) {
        if (numericUserId == null) {
            return jwtPermissions(jwt);
        }

        // OpenFGA-first: check each module from catalog
        String userId = String.valueOf(numericUserId);
        Set<String> resolved = new LinkedHashSet<>();

        for (String module : catalogService.getModuleKeys()) {
            if (authzService.check(userId, "can_manage", "module", module)) {
                resolved.add(module);
            } else if (authzService.check(userId, "can_view", "module", module)) {
                resolved.add(module);
            }
        }

        if (!resolved.isEmpty()) {
            return resolved;
        }

        // Fallback: DB-based resolution (legacy)
        Set<String> dbPermissions = permissionService.getAssignments(numericUserId, null, null, null).stream()
                .flatMap(assignment -> assignment.getPermissions() == null
                        ? java.util.stream.Stream.empty()
                        : assignment.getPermissions().stream())
                .filter(permission -> permission != null && !permission.isBlank())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        return dbPermissions;
    }

    private AuthenticatedUserLookupService.ResolvedAuthenticatedUser resolveAuthenticatedUser(Jwt jwt) {
        try {
            return authenticatedUserLookupService.resolve(jwt);
        } catch (RuntimeException ex) {
            log.warn("Authz /me kullanıcı çözümleme başarısız; JWT fallback kullanılacak. cause={}", ex.getMessage());
            return new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(
                    null,
                    fallbackResponseUserId(jwt),
                    fallbackEmail(jwt)
            );
        }
    }

    /**
     * Check if the user is an organization admin via OpenFGA.
     * Falls back to false on any error (fail-closed).
     */
    private boolean checkOrganizationAdmin(Long numericUserId) {
        if (numericUserId == null) {
            return false;
        }
        try {
            return authzService.check(String.valueOf(numericUserId), "admin", "organization", "default");
        } catch (RuntimeException ex) {
            log.warn("Authz /me OpenFGA organization admin check failed; defaulting to false. cause={}", ex.getMessage());
            return false;
        }
    }

    private Set<String> resolvePermissionsSafely(Jwt jwt, Long numericUserId) {
        try {
            return resolvePermissions(jwt, numericUserId);
        } catch (RuntimeException ex) {
            log.warn("Authz /me izin çözümleme başarısız; JWT permissions fallback kullanılacak. cause={}", ex.getMessage());
            return jwtPermissions(jwt);
        }
    }

    private Map<String, Set<Long>> resolveScopeSummarySafely(Long numericUserId) {
        if (numericUserId == null) {
            return Collections.emptyMap();
        }
        try {
            return authorizationQueryService.getUserScopeSummary(numericUserId);
        } catch (RuntimeException ex) {
            log.warn("Authz /me scope summary çözümleme başarısız; boş scope dönülecek. cause={}", ex.getMessage());
            return Collections.emptyMap();
        }
    }

    private void populateEnhancedFieldsSafely(AuthzMeResponseDto dto, Long numericUserId) {
        try {
            populateEnhancedFields(dto, numericUserId);
        } catch (RuntimeException ex) {
            log.warn("Authz /me enhanced field çözümleme başarısız; boş enhanced alanlarla devam edilecek. cause={}", ex.getMessage());
        }
    }

    private void applyFrontendCompatibilityFallback(AuthzMeResponseDto dto, Jwt jwt) {
        if (dto.getPermissions() == null) {
            dto.setPermissions(Set.of());
        }
        if (dto.getScopes() == null) {
            dto.setScopes(List.of());
        }
        if (dto.getAllowedScopes() == null) {
            dto.setAllowedScopes(List.of());
        }
        if (dto.getRoles() == null) {
            dto.setRoles(extractJwtRoles(jwt));
        }
        if (dto.getActions() == null) {
            dto.setActions(Map.of());
        }
        if (dto.getReports() == null) {
            dto.setReports(Map.of());
        }
        if (dto.getPages() == null) {
            dto.setPages(Map.of());
        }

        Map<String, String> moduleGrants = dto.getModules();
        if (moduleGrants == null || moduleGrants.isEmpty()) {
            moduleGrants = deriveModuleGrants(dto.getPermissions(), dto.getRoles());
            dto.setModules(moduleGrants);
        }

        if (dto.getAllowedModules() == null || dto.getAllowedModules().isEmpty()) {
            dto.setAllowedModules(List.copyOf(moduleGrants.keySet()));
        }
    }

    private AuthzMeResponseDto buildJwtFallbackResponse(Jwt jwt) {
        AuthzMeResponseDto dto = new AuthzMeResponseDto();
        Set<String> permissions = safeJwtPermissions(jwt);
        List<String> roles = extractJwtRoles(jwt);
        Map<String, String> moduleGrants = deriveModuleGrants(permissions, roles);

        dto.setUserId(fallbackResponseUserId(jwt));
        dto.setPermissions(permissions);
        dto.setAllowedModules(List.copyOf(moduleGrants.keySet()));
        dto.setScopes(List.of());
        dto.setAllowedScopes(List.of());
        dto.setRoles(roles);
        dto.setModules(moduleGrants);
        dto.setActions(Map.of());
        dto.setReports(Map.of());
        dto.setPages(Map.of());
        dto.setSuperAdmin(
                permissions.stream().anyMatch(p -> p != null && p.equalsIgnoreCase("admin"))
                        || roles.stream().anyMatch(role -> role != null && role.equalsIgnoreCase("admin"))
        );
        return dto;
    }

    private Set<String> safeJwtPermissions(Jwt jwt) {
        try {
            return jwtPermissions(jwt);
        } catch (RuntimeException ex) {
            log.warn("JWT permissions parse edilemedi; boş permissions döndürülecek. cause={}", ex.getMessage());
            return Set.of();
        }
    }

    private List<String> extractJwtRoles(Jwt jwt) {
        if (jwt == null) {
            return List.of();
        }
        Object realmAccessClaim = jwt.getClaim("realm_access");
        if (!(realmAccessClaim instanceof Map<?, ?> realmAccess)) {
            return List.of();
        }
        Object rolesClaim = realmAccess.get("roles");
        if (!(rolesClaim instanceof Collection<?> rolesCollection)) {
            return List.of();
        }
        return rolesCollection.stream()
                .map(String::valueOf)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
    }

    private Map<String, String> deriveModuleGrants(Set<String> permissions, List<String> roles) {
        Map<String, String> modules = new LinkedHashMap<>();

        if (permissions != null) {
            for (String permission : permissions) {
                registerModuleGrant(modules, permission);
            }
        }

        if (roles != null) {
            for (String role : roles) {
                if (role != null && role.equalsIgnoreCase("admin")) {
                    for (String module : catalogService.getModuleKeys()) {
                        upsertGrant(modules, module, "MANAGE");
                    }
                }
            }
        }

        return modules;
    }

    private void registerModuleGrant(Map<String, String> modules, String rawPermission) {
        String value = rawPermission == null ? "" : rawPermission.trim().toUpperCase(Locale.ROOT);
        if (value.isEmpty()) {
            return;
        }

        switch (value) {
            case "ACCESS", "ACCESS-READ", "VIEW_ACCESS", "ACCESS-WRITE", "ACCESS-MANAGE", "ROLE-READ", "ROLE-WRITE", "ROLE-MANAGE", "PERMISSION-MANAGE" ->
                    upsertGrant(modules, "ACCESS", value.contains("MANAGE") || value.contains("WRITE") ? "MANAGE" : "VIEW");
            case "AUDIT", "AUDIT-READ", "VIEW_AUDIT" ->
                    upsertGrant(modules, "AUDIT", "VIEW");
            case "REPORT", "REPORT_VIEW", "VIEW_REPORTS", "REPORT_EXPORT", "REPORT_MANAGE" ->
                    upsertGrant(modules, "REPORT", value.contains("MANAGE") ? "MANAGE" : "VIEW");
            case "THEME", "THEME_ADMIN" ->
                    upsertGrant(modules, "THEME", value.contains("ADMIN") ? "MANAGE" : "VIEW");
            case "USER_MANAGEMENT", "USER-READ", "VIEW_USERS", "MANAGE_USERS", "USER-UPDATE", "USER-CREATE", "USER-DELETE", "USER-EXPORT", "USER-IMPORT" ->
                    upsertGrant(modules, "USER_MANAGEMENT", value.contains("MANAGE") || value.contains("CREATE") || value.contains("UPDATE") || value.contains("DELETE") ? "MANAGE" : "VIEW");
            case "WAREHOUSE", "WAREHOUSE_VIEW", "WAREHOUSE_MANAGE" ->
                    upsertGrant(modules, "WAREHOUSE", value.contains("MANAGE") ? "MANAGE" : "VIEW");
            case "PURCHASE", "PURCHASE_VIEW", "PURCHASE_MANAGE", "CREATE_PO", "DELETE_PO", "APPROVE_PURCHASE" ->
                    upsertGrant(modules, "PURCHASE", value.contains("MANAGE") || value.equals("CREATE_PO") || value.equals("DELETE_PO") || value.equals("APPROVE_PURCHASE") ? "MANAGE" : "VIEW");
            default -> {
                if (catalogService.getModuleKeys().contains(value)) {
                    upsertGrant(modules, value, "VIEW");
                }
            }
        }
    }

    private void upsertGrant(Map<String, String> modules, String module, String candidateGrant) {
        if (module == null || module.isBlank()) {
            return;
        }
        String normalizedGrant = "MANAGE".equalsIgnoreCase(candidateGrant) ? "MANAGE" : "VIEW";
        String existingGrant = modules.get(module);
        if (existingGrant == null || ("MANAGE".equals(normalizedGrant) && !"MANAGE".equals(existingGrant))) {
            modules.put(module, normalizedGrant);
        }
    }

    private String fallbackResponseUserId(Jwt jwt) {
        return firstNonBlank(
                stringClaim(jwt, "userId"),
                stringClaim(jwt, "uid"),
                jwt.getSubject(),
                fallbackEmail(jwt)
        );
    }

    private String fallbackEmail(Jwt jwt) {
        return firstNonBlank(jwt.getClaimAsString("email"), jwt.getClaimAsString("preferred_username"));
    }

    private Set<String> jwtPermissions(Jwt jwt) {
        List<String> permissions = jwt.getClaimAsStringList("permissions");
        if (permissions == null || permissions.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(permissions);
    }

    private String stringClaim(Jwt jwt, String claimName) {
        Object value = jwt.getClaim(claimName);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}

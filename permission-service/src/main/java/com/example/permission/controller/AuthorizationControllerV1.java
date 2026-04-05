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
        AuthzMeResponseDto dto = new AuthzMeResponseDto();
        var resolvedUser = authenticatedUserLookupService.resolve(jwt);
        dto.setUserId(resolvedUser.responseUserId());

        Long numericUserId = resolvedUser.numericUserId();

        // Legacy permissions (backward compat)
        dto.setPermissions(resolvePermissions(jwt, numericUserId));
        boolean isSuperAdmin = dto.getPermissions().stream()
                .anyMatch(p -> p != null && p.equalsIgnoreCase("admin"));
        dto.setSuperAdmin(isSuperAdmin);

        // Scopes (existing)
        Map<String, Set<Long>> scopeSummary = numericUserId == null
                ? Collections.emptyMap()
                : authorizationQueryService.getUserScopeSummary(numericUserId);
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
            populateEnhancedFields(dto, numericUserId);
        }

        return ResponseEntity.ok(dto);
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

        // Get user's roles
        List<UserRoleAssignment> assignments = assignmentRepository.findActiveAssignments(userId);
        List<String> userRoles = assignments.stream()
                .map(a -> a.getRole().getName())
                .distinct()
                .toList();

        if (assignments.isEmpty()) {
            Map<String, List<Long>> userScopes = buildScopeMap(userId);
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

        Map<String, List<Long>> userScopes = buildScopeMap(userId);

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
            Set<String> jwtPermissions = jwt.getClaimAsStringList("permissions") == null
                    ? Set.of()
                    : Set.copyOf(jwt.getClaimAsStringList("permissions"));
            return jwtPermissions;
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
}

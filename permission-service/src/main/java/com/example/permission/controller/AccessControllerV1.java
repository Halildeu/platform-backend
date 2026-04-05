package com.example.permission.controller;

import com.example.permission.dto.access.AccessRoleDto;
import com.example.permission.dto.v1.BulkPermissionsRequestDto;
import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.CloneRoleRequestDto;
import com.example.permission.dto.v1.PagedResultDto;
import com.example.permission.dto.v1.PermissionDtoMapper;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.dto.v1.RoleDto;
import com.example.permission.dto.v1.RolePermissionsUpdateRequestDto;
import com.example.permission.dto.v1.RolePermissionsUpdateResponseDto;
import com.example.permission.dto.v1.ScopeAssignmentRequestDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.RoleRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AccessRoleService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.service.UserScopeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * v1 roles endpoint'leri; STORY-0318: members, granules eklendi.
 */
@RestController
@RequestMapping("/api/v1/roles")
public class AccessControllerV1 {

    private final AccessRoleService accessRoleService;
    private final UserScopeService userScopeService;
    private final UserRoleAssignmentRepository assignmentRepository;
    private final RolePermissionRepository rolePermissionRepository;
    private final RoleRepository roleRepository;
    private final PermissionService permissionService;
    private final TupleSyncService tupleSyncService;

    public AccessControllerV1(AccessRoleService accessRoleService,
                              UserScopeService userScopeService,
                              UserRoleAssignmentRepository assignmentRepository,
                              RolePermissionRepository rolePermissionRepository,
                              RoleRepository roleRepository,
                              PermissionService permissionService,
                              TupleSyncService tupleSyncService) {
        this.accessRoleService = accessRoleService;
        this.userScopeService = userScopeService;
        this.assignmentRepository = assignmentRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.roleRepository = roleRepository;
        this.permissionService = permissionService;
        this.tupleSyncService = tupleSyncService;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('access-read')")
    public ResponseEntity<PagedResultDto<RoleDto>> listRoles() {
        List<AccessRoleDto> roles = accessRoleService.listRoles();
        List<RoleDto> items = roles.stream().map(PermissionDtoMapper::toRoleDto).toList();
        return ResponseEntity.ok(PermissionDtoMapper.wrap(items, items.size()));
    }

    @GetMapping("/{roleId}")
    @PreAuthorize("hasAuthority('access-read')")
    public ResponseEntity<RoleDto> getRole(@PathVariable Long roleId) {
        AccessRoleDto role = accessRoleService.getRole(roleId);
        return ResponseEntity.ok(PermissionDtoMapper.toRoleDto(role));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('role-manage')")
    public ResponseEntity<RoleDto> createRole(@RequestBody CloneRoleRequestDto request) {
        if (request.getName() == null || request.getName().trim().length() < 3) {
            return ResponseEntity.badRequest().build();
        }
        RoleDto created = accessRoleService.createRole(
                request.getName().trim(),
                request.getDescription(),
                request.getPerformedBy()
        );
        return ResponseEntity.status(201).body(created);
    }

    @DeleteMapping("/{roleId}")
    @PreAuthorize("hasAuthority('role-manage')")
    public ResponseEntity<Void> deleteRole(@PathVariable Long roleId,
                                           @RequestParam(value = "performedBy", required = false) Long performedBy) {
        accessRoleService.deleteRole(roleId, performedBy);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{roleId}/clone")
    @PreAuthorize("hasAuthority('role-manage')")
    public ResponseEntity<RoleCloneResponseDto> cloneRole(@PathVariable Long roleId,
                                                         @RequestBody(required = false) CloneRoleRequestDto request) {
        CloneRoleRequestDto payload = request == null ? new CloneRoleRequestDto() : request;
        RoleCloneResponseDto result = accessRoleService.cloneRole(
                roleId,
                payload.getName(),
                payload.getDescription(),
                payload.getPerformedBy()
        );
        return ResponseEntity.ok(result);
    }

    @PatchMapping("/{roleId}/permissions/bulk")
    @PreAuthorize("hasAuthority('permission-manage')")
    public ResponseEntity<BulkPermissionsResponseDto> bulkPermissions(@PathVariable Long roleId,
                                                                @RequestBody BulkPermissionsRequestDto request) {
        BulkPermissionsResponseDto result = accessRoleService.bulkUpdateModuleLevel(
                List.of(roleId),
                request.getModuleKey(),
                request.getModuleLabel(),
                request.getLevel(),
                request.getPerformedBy()
        );
        return ResponseEntity.ok(result);
    }

    @PutMapping("/{roleId}/permissions")
    @PreAuthorize("hasAuthority('permission-manage')")
    public ResponseEntity<RolePermissionsUpdateResponseDto> updateRolePermissions(@PathVariable Long roleId,
                                                                                  @RequestBody(required = false) RolePermissionsUpdateRequestDto request) {
        RolePermissionsUpdateRequestDto payload = request == null ? new RolePermissionsUpdateRequestDto() : request;
        RolePermissionsUpdateResponseDto result = accessRoleService.updateRolePermissions(
                roleId,
                payload.getPermissionIds(),
                payload.getPerformedBy()
        );
        return ResponseEntity.ok(result);
    }

    /**
     * Get users assigned to a role (bidirectional: role → users).
     */
    @GetMapping("/{roleId}/members")
    public ResponseEntity<List<Map<String, Object>>> getRoleMembers(@PathVariable Long roleId) {
        var assignments = assignmentRepository.findByRoleIdAndActiveTrue(roleId);
        List<Map<String, Object>> members = assignments.stream()
                .map(a -> {
                    Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("userId", a.getUserId());
                    m.put("assignedAt", a.getAssignedAt() != null ? a.getAssignedAt().toString() : null);
                    return m;
                })
                .toList();
        return ResponseEntity.ok(members);
    }

    /**
     * Add user(s) to a role (bidirectional: role → user assignment).
     */
    @PostMapping("/{roleId}/members")
    @PreAuthorize("hasAuthority('permission-manage')")
    public ResponseEntity<Map<String, Object>> addRoleMembers(@PathVariable Long roleId,
                                                               @RequestBody Map<String, List<Long>> body) {
        List<Long> userIds = body.getOrDefault("userIds", List.of());
        for (Long userId : userIds) {
            var existing = assignmentRepository.findActiveAssignment(userId, null, roleId, null, null);
            if (existing.isEmpty()) {
                var req = new com.example.permission.dto.PermissionAssignRequest();
                req.setUserId(userId);
                req.setRoleId(roleId);
                permissionService.assignRole(req);
            }
        }
        return ResponseEntity.ok(Map.of("roleId", roleId, "addedUserIds", userIds));
    }

    /**
     * Remove a user from a role.
     */
    @DeleteMapping("/{roleId}/members/{userId}")
    @PreAuthorize("hasAuthority('permission-manage')")
    public ResponseEntity<Void> removeRoleMember(@PathVariable Long roleId, @PathVariable Long userId) {
        var assignments = assignmentRepository.findActiveAssignments(userId);
        assignments.stream()
                .filter(a -> a.getRole().getId().equals(roleId))
                .forEach(a -> permissionService.revokeRole(a.getId(), null));
        return ResponseEntity.noContent().build();
    }

    /**
     * Update role permissions with 5-granule format + DENY support.
     * Triggers tuple propagation for all assigned users.
     */
    @PutMapping("/{roleId}/granules")
    @PreAuthorize("hasAuthority('permission-manage')")
    public ResponseEntity<Map<String, Object>> updateRoleGranules(
            @PathVariable Long roleId,
            @RequestBody Map<String, List<com.example.permission.dto.v1.RolePermissionItemDto>> body) {
        var role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));

        var items = body.getOrDefault("permissions", List.of());

        // Delete old granule-based permissions
        rolePermissionRepository.deleteByRoleId(roleId);

        // Insert new
        for (var item : items) {
            var rp = new com.example.permission.model.RolePermission(
                    role,
                    com.example.permission.model.PermissionType.valueOf(item.type().toUpperCase()),
                    item.key(),
                    com.example.permission.model.GrantType.valueOf(item.grant().toUpperCase())
            );
            rolePermissionRepository.save(rp);
        }

        role.setUpdatedAt(java.time.Instant.now());
        roleRepository.save(role);

        // Propagate to all assigned users
        tupleSyncService.propagateRoleChange(roleId);

        return ResponseEntity.ok(Map.of("roleId", roleId, "granuleCount", items.size(), "propagated", true));
    }

    @GetMapping("/users/{userId}/scopes")
    @PreAuthorize("hasAuthority('permission-scope-manage')")
    public ResponseEntity<List<ScopeSummaryDto>> listUserScopes(@PathVariable Long userId) {
        return ResponseEntity.ok(userScopeService.listUserScopes(userId));
    }

    @PostMapping("/users/{userId}/scopes")
    @PreAuthorize("hasAuthority('permission-scope-manage')")
    public ResponseEntity<Void> addUserScope(@PathVariable Long userId,
                                             @RequestBody ScopeAssignmentRequestDto scope) {
        userScopeService.addScope(userId, scope.scopeType(), scope.scopeRefId(), scope.permissionCode());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/users/{userId}/scopes/{scopeType}/{scopeRefId}")
    @PreAuthorize("hasAuthority('permission-scope-manage')")
    public ResponseEntity<Void> removeUserScope(@PathVariable Long userId,
                                                @PathVariable String scopeType,
                                                @PathVariable Long scopeRefId,
                                                @RequestParam("permissionCode") String permissionCode) {
        userScopeService.removeScope(userId, scopeType, scopeRefId, permissionCode);
        return ResponseEntity.noContent().build();
    }
}

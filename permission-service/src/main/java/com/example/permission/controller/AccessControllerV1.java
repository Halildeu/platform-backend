package com.example.permission.controller;

import com.example.permission.dto.access.AccessRoleDto;
import com.example.permission.dto.v1.BulkPermissionsRequestDto;
import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.CloneRoleRequestDto;
import com.example.permission.dto.v1.PagedResultDto;
import com.example.permission.dto.v1.PermissionDtoMapper;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.dto.v1.RoleDto;
import com.example.permission.dto.v1.ScopeAssignmentRequestDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.service.AccessRoleService;
import com.example.permission.service.UserScopeService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.List;
/**
 * v1 roles endpoint'leri; legacy AccessController korunur.
 */
@RestController
@RequestMapping("/api/v1/roles")
@PreAuthorize("hasAuthority('permission-scope-manage')")
public class AccessControllerV1 {

    private final AccessRoleService accessRoleService;
    private final UserScopeService userScopeService;

    public AccessControllerV1(AccessRoleService accessRoleService,
                              UserScopeService userScopeService) {
        this.accessRoleService = accessRoleService;
        this.userScopeService = userScopeService;
    }

    @GetMapping
    public ResponseEntity<PagedResultDto<RoleDto>> listRoles() {
        List<AccessRoleDto> roles = accessRoleService.listRoles();
        List<RoleDto> items = roles.stream().map(PermissionDtoMapper::toRoleDto).toList();
        return ResponseEntity.ok(PermissionDtoMapper.wrap(items, items.size()));
    }

    @PostMapping("/{roleId}/clone")
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

    @GetMapping("/users/{userId}/scopes")
    public ResponseEntity<List<ScopeSummaryDto>> listUserScopes(@PathVariable Long userId) {
        return ResponseEntity.ok(userScopeService.listUserScopes(userId));
    }

    @PostMapping("/users/{userId}/scopes")
    public ResponseEntity<Void> addUserScope(@PathVariable Long userId,
                                             @RequestBody ScopeAssignmentRequestDto scope) {
        userScopeService.addScope(userId, scope.scopeType(), scope.scopeRefId(), scope.permissionCode());
        return ResponseEntity.ok().build();
    }

    @DeleteMapping("/users/{userId}/scopes/{scopeType}/{scopeRefId}")
    public ResponseEntity<Void> removeUserScope(@PathVariable Long userId,
                                                @PathVariable String scopeType,
                                                @PathVariable Long scopeRefId,
                                                @RequestParam("permissionCode") String permissionCode) {
        userScopeService.removeScope(userId, scopeType, scopeRefId, permissionCode);
        return ResponseEntity.noContent().build();
    }
}

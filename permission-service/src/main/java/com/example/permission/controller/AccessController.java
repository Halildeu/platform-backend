package com.example.permission.controller;

import com.example.permission.dto.access.BulkPermissionsRequest;
import com.example.permission.dto.access.CloneRoleRequest;
import com.example.permission.dto.v1.BulkPermissionsResponseDto;
import com.example.permission.dto.v1.PermissionDtoMapper;
import com.example.permission.dto.v1.RoleCloneResponseDto;
import com.example.permission.service.AccessRoleService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

@RestController
@RequestMapping("/api/access/roles")
@Deprecated(since = "v1 endpoints added; use /api/v1/roles")
public class AccessController {

    private final AccessRoleService accessRoleService;

    public AccessController(AccessRoleService accessRoleService) {
        this.accessRoleService = accessRoleService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> listRoles() {
        var items = accessRoleService.listRoles();
        return ResponseEntity.ok(Map.of("items", items, "total", items.size()));
    }

    @PostMapping("/clone")
    public ResponseEntity<Map<String, Object>> cloneRole(@RequestBody CloneRoleRequest request) {
        RoleCloneResponseDto result = accessRoleService.cloneRole(
                request.getSourceRoleId(), request.getName(), request.getDescription(), request.getPerformedBy());
        Map<String, Object> payload = new HashMap<>();
        payload.put("role", PermissionDtoMapper.toAccessRoleDto(result.getRole()));
        payload.put("auditId", result.getAuditId());
        return ResponseEntity.ok(payload);
    }

    @PatchMapping("/bulk-permissions")
    public ResponseEntity<Map<String, Object>> bulkPermissions(@RequestBody BulkPermissionsRequest request) {
        BulkPermissionsResponseDto result = accessRoleService.bulkUpdateModuleLevel(
                request.getRoleIds(), request.getModuleKey(), request.getModuleLabel(), request.getLevel(), request.getPerformedBy());
        Map<String, Object> payload = new HashMap<>();
        payload.put("updatedRoleIds", result.getUpdatedRoleIds());
        payload.put("auditId", result.getAuditId());
        return ResponseEntity.ok(payload);
    }
}

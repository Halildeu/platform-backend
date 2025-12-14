package com.example.permission.controller;

import com.example.permission.dto.PermissionCheckRequest;
import com.example.permission.dto.MutationAckResponse;
import com.example.permission.dto.PermissionAssignRequest;
import com.example.permission.dto.PermissionAssignmentUpdateRequest;
import com.example.permission.dto.PermissionResponse;
import com.example.permission.service.PermissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/permissions")
@Deprecated(since = "v1 endpoints added; use /api/v1/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    @PostMapping("/check")
    public ResponseEntity<Boolean> checkPermission(@Valid @RequestBody PermissionCheckRequest request) {
        boolean allowed = permissionService.hasPermission(
                request.getUserId(),
                request.getCompanyId(),
                request.getProjectId(),
                request.getWarehouseId(),
                request.getAction()
        );
        return ResponseEntity.ok(allowed);
    }

    @PostMapping("/assign")
    public ResponseEntity<PermissionResponse> assignRole(@Valid @RequestBody PermissionAssignRequest request) {
        PermissionResponse response = permissionService.assignRole(request);
        return new ResponseEntity<>(response, HttpStatus.CREATED);
    }

    @DeleteMapping("/assign/{assignmentId}")
    public ResponseEntity<MutationAckResponse> revokeRole(@PathVariable Long assignmentId,
                                                          @RequestParam Long performedBy) {
        String auditId = permissionService.revokeRole(assignmentId, performedBy);
        return ResponseEntity.ok(new MutationAckResponse("ok", auditId));
    }

    @GetMapping("/assignments")
    public ResponseEntity<List<PermissionResponse>> listAssignments(@RequestParam Long userId,
                                                                    @RequestParam(required = false) Long companyId,
                                                                    @RequestParam(required = false) Long projectId,
                                                                    @RequestParam(required = false) Long warehouseId) {
        return ResponseEntity.ok(permissionService.getAssignments(userId, companyId, projectId, warehouseId));
    }

    @PatchMapping("/assignments")
    public ResponseEntity<PermissionResponse> updateAssignment(@Valid @RequestBody PermissionAssignmentUpdateRequest request) {
        PermissionResponse response = permissionService.updateAssignment(request);
        return ResponseEntity.ok(response);
    }
}

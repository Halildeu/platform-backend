package com.example.permission.controller;

import com.example.permission.dto.PermissionResponse;
import com.example.permission.dto.v1.PagedResultDto;
import com.example.permission.dto.v1.PermissionAssignRequestDto;
import com.example.permission.dto.v1.PermissionAssignmentDto;
import com.example.permission.dto.v1.PermissionCatalogItemDto;
import com.example.permission.dto.v1.PermissionAssignmentUpdateRequestDto;
import com.example.permission.dto.v1.PermissionCheckRequestDto;
import com.example.permission.dto.v1.PermissionCheckResultDto;
import com.example.permission.dto.v1.PermissionDtoMapper;
import com.example.permission.dto.v1.PermissionMutationAckDto;
import com.example.permission.model.Permission;
import com.example.permission.repository.PermissionRepository;
import com.example.permission.service.PermissionService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * v1 REST path'leri; legacy controller davranışı korunur.
 */
@RestController
@RequestMapping("/api/v1/permissions")
public class PermissionControllerV1 {

    private final PermissionService permissionService;
    private final PermissionRepository permissionRepository;

    public PermissionControllerV1(PermissionService permissionService,
                                  PermissionRepository permissionRepository) {
        this.permissionService = permissionService;
        this.permissionRepository = permissionRepository;
    }

    @GetMapping
    public ResponseEntity<PagedResultDto<PermissionCatalogItemDto>> listPermissions() {
        List<PermissionCatalogItemDto> items = permissionRepository.findAll().stream()
                .sorted(java.util.Comparator.comparing(Permission::getCode, String.CASE_INSENSITIVE_ORDER))
                .map(PermissionDtoMapper::toPermissionCatalogItemDto)
                .toList();
        return ResponseEntity.ok(PermissionDtoMapper.wrap(items, items.size()));
    }

    /** @deprecated Use OpenFGA check() directly instead. This endpoint will be removed. */
    @Deprecated
    @PostMapping("/check")
    public ResponseEntity<PermissionCheckResultDto> checkPermission(@Valid @RequestBody PermissionCheckRequestDto request) {
        boolean allowed = permissionService.hasPermission(
                request.getUserId(),
                request.getCompanyId(),
                request.getProjectId(),
                request.getWarehouseId(),
                request.getAction()
        );
        return ResponseEntity.ok(new PermissionCheckResultDto(allowed));
    }

    @PostMapping("/assignments")
    public ResponseEntity<PermissionAssignmentDto> assignRole(@Valid @RequestBody PermissionAssignRequestDto request) {
        PermissionResponse response = permissionService.assignRole(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(PermissionDtoMapper.toAssignmentDto(response));
    }

    @DeleteMapping("/assignments/{assignmentId}")
    public ResponseEntity<PermissionMutationAckDto> revokeRole(@PathVariable Long assignmentId,
                                                               @RequestParam Long performedBy) {
        String auditId = permissionService.revokeRole(assignmentId, performedBy);
        return ResponseEntity.ok(new PermissionMutationAckDto("ok", auditId));
    }

    @GetMapping("/assignments")
    public ResponseEntity<PagedResultDto<PermissionAssignmentDto>> listAssignments(@RequestParam Long userId,
                                                                                   @RequestParam(required = false) Long companyId,
                                                                                   @RequestParam(required = false) Long projectId,
                                                                                   @RequestParam(required = false) Long warehouseId) {
        List<PermissionResponse> responses = permissionService.getAssignments(userId, companyId, projectId, warehouseId);
        List<PermissionAssignmentDto> items = responses.stream().map(PermissionDtoMapper::toAssignmentDto).toList();
        return ResponseEntity.ok(PermissionDtoMapper.wrap(items, items.size()));
    }

    @PostMapping("/assignments/update")
    public ResponseEntity<PermissionAssignmentDto> updateAssignment(@Valid @RequestBody PermissionAssignmentUpdateRequestDto request) {
        PermissionResponse response = permissionService.updateAssignment(request);
        return ResponseEntity.ok(PermissionDtoMapper.toAssignmentDto(response));
    }
}

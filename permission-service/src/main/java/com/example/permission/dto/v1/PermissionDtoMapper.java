package com.example.permission.dto.v1;

import com.example.permission.dto.PermissionResponse;
import com.example.permission.dto.access.AccessModulePolicyDto;
import com.example.permission.dto.access.AccessRoleDto;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class PermissionDtoMapper {

    private PermissionDtoMapper() {
    }

    public static PermissionAssignmentDto toAssignmentDto(PermissionResponse response) {
        if (response == null) return null;
        PermissionAssignmentDto dto = new PermissionAssignmentDto();
        dto.setAssignmentId(response.getAssignmentId());
        dto.setUserId(response.getUserId());
        dto.setCompanyId(response.getCompanyId());
        dto.setProjectId(response.getProjectId());
        dto.setWarehouseId(response.getWarehouseId());
        dto.setRoleId(response.getRoleId());
        dto.setRoleName(response.getRoleName());
        dto.setModuleKey(response.getModuleKey());
        dto.setModuleLabel(response.getModuleLabel());
        dto.setActive(response.isActive());
        dto.setAssignedAt(response.getAssignedAt());
        dto.setRevokedAt(response.getRevokedAt());
        dto.setPermissions(response.getPermissions());
        dto.setPermissionModules(response.getPermissionModules());
        dto.setAuditId(response.getAuditId());
        return dto;
    }

    public static RoleDto toRoleDto(AccessRoleDto accessRoleDto) {
        if (accessRoleDto == null) return null;
        RoleDto dto = new RoleDto();
        dto.setId(accessRoleDto.id());
        dto.setName(accessRoleDto.name());
        dto.setDescription(accessRoleDto.description());
        dto.setMemberCount(accessRoleDto.memberCount());
        dto.setSystemRole(accessRoleDto.systemRole());
        dto.setLastModifiedAt(accessRoleDto.lastModifiedAt());
        dto.setLastModifiedBy(accessRoleDto.lastModifiedBy());
        List<RolePolicyDto> policies = accessRoleDto.policies() == null ? List.of() :
                accessRoleDto.policies().stream().filter(Objects::nonNull).map(PermissionDtoMapper::toPolicyDto).toList();
        dto.setPolicies(policies);
        return dto;
    }

    public static AccessRoleDto toAccessRoleDto(RoleDto roleDto) {
        if (roleDto == null) return null;
        List<AccessModulePolicyDto> policies = roleDto.getPolicies() == null ? List.of() :
                roleDto.getPolicies().stream()
                        .filter(Objects::nonNull)
                        .map(PermissionDtoMapper::toAccessPolicyDto)
                        .toList();
        return new AccessRoleDto(
                roleDto.getId(),
                roleDto.getName(),
                roleDto.getDescription(),
                roleDto.getMemberCount(),
                roleDto.isSystemRole(),
                roleDto.getLastModifiedAt(),
                roleDto.getLastModifiedBy(),
                policies
        );
    }

    private static RolePolicyDto toPolicyDto(AccessModulePolicyDto policyDto) {
        RolePolicyDto dto = new RolePolicyDto();
        dto.setModuleKey(policyDto.moduleKey());
        dto.setModuleLabel(policyDto.moduleLabel());
        dto.setLevel(policyDto.level());
        dto.setLastUpdatedAt(policyDto.lastUpdatedAt());
        dto.setUpdatedBy(policyDto.updatedBy());
        return dto;
    }

    private static AccessModulePolicyDto toAccessPolicyDto(RolePolicyDto policyDto) {
        return new AccessModulePolicyDto(
                policyDto.getModuleKey(),
                policyDto.getModuleLabel(),
                policyDto.getLevel(),
                policyDto.getLastUpdatedAt(),
                policyDto.getUpdatedBy()
        );
    }

    public static <T> PagedResultDto<T> wrap(List<T> items, long total) {
        return new PagedResultDto<>(items, total, null, null);
    }
}

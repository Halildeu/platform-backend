package com.example.permission.dto.access;

import java.util.List;

public record AccessRoleDto(
        Long id,
        String name,
        String description,
        long memberCount,
        boolean systemRole,
        String lastModifiedAt,
        String lastModifiedBy,
        List<AccessModulePolicyDto> policies
) {}


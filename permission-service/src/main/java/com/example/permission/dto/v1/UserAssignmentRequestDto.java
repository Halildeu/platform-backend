package com.example.permission.dto.v1;

import java.util.List;

public record UserAssignmentRequestDto(
        List<Long> roleIds,
        ScopeAssignmentDto scopes
) {
    public record ScopeAssignmentDto(
            List<Long> companyIds,
            List<Long> projectIds,
            List<Long> warehouseIds,
            List<Long> branchIds
    ) {}
}

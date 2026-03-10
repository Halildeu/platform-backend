package com.example.permission.dto.v1;

import java.util.List;

public class RolePermissionsUpdateRequestDto {

    private List<String> permissionIds;
    private Long performedBy;

    public List<String> getPermissionIds() {
        return permissionIds;
    }

    public void setPermissionIds(List<String> permissionIds) {
        this.permissionIds = permissionIds;
    }

    public Long getPerformedBy() {
        return performedBy;
    }

    public void setPerformedBy(Long performedBy) {
        this.performedBy = performedBy;
    }
}

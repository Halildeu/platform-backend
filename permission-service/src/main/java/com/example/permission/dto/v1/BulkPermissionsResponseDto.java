package com.example.permission.dto.v1;

import java.util.List;

public class BulkPermissionsResponseDto {

    private List<Long> updatedRoleIds;
    private String auditId;

    public BulkPermissionsResponseDto() {
    }

    public BulkPermissionsResponseDto(List<Long> updatedRoleIds, String auditId) {
        this.updatedRoleIds = updatedRoleIds;
        this.auditId = auditId;
    }

    public List<Long> getUpdatedRoleIds() {
        return updatedRoleIds;
    }

    public void setUpdatedRoleIds(List<Long> updatedRoleIds) {
        this.updatedRoleIds = updatedRoleIds;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }
}

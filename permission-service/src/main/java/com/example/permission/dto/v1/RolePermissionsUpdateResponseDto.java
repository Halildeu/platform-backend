package com.example.permission.dto.v1;

public class RolePermissionsUpdateResponseDto {

    private boolean updated;
    private String auditId;

    public RolePermissionsUpdateResponseDto() {
    }

    public RolePermissionsUpdateResponseDto(boolean updated, String auditId) {
        this.updated = updated;
        this.auditId = auditId;
    }

    public boolean isUpdated() {
        return updated;
    }

    public void setUpdated(boolean updated) {
        this.updated = updated;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }
}

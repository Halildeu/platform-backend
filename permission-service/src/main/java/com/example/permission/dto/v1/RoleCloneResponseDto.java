package com.example.permission.dto.v1;

public class RoleCloneResponseDto {

    private RoleDto role;
    private String auditId;

    public RoleCloneResponseDto() {
    }

    public RoleCloneResponseDto(RoleDto role, String auditId) {
        this.role = role;
        this.auditId = auditId;
    }

    public RoleDto getRole() {
        return role;
    }

    public void setRole(RoleDto role) {
        this.role = role;
    }

    public String getAuditId() {
        return auditId;
    }

    public void setAuditId(String auditId) {
        this.auditId = auditId;
    }
}

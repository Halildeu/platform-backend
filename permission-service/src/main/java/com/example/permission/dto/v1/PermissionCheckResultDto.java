package com.example.permission.dto.v1;

public class PermissionCheckResultDto {
    private boolean allowed;

    public PermissionCheckResultDto() {}

    public PermissionCheckResultDto(boolean allowed) {
        this.allowed = allowed;
    }

    public boolean isAllowed() {
        return allowed;
    }

    public void setAllowed(boolean allowed) {
        this.allowed = allowed;
    }
}

package com.example.permission.dto.v1;

import java.util.List;

public class AuthzPermissionScopesDto {
    private String permission;
    private List<AuthzScopeDto> scopes;

    public AuthzPermissionScopesDto() {
    }

    public AuthzPermissionScopesDto(String permission, List<AuthzScopeDto> scopes) {
        this.permission = permission;
        this.scopes = scopes;
    }

    public String getPermission() {
        return permission;
    }

    public void setPermission(String permission) {
        this.permission = permission;
    }

    public List<AuthzScopeDto> getScopes() {
        return scopes;
    }

    public void setScopes(List<AuthzScopeDto> scopes) {
        this.scopes = scopes;
    }
}

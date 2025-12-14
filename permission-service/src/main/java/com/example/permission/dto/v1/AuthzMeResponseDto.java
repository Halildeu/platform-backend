package com.example.permission.dto.v1;

import java.util.List;
import java.util.Set;

public class AuthzMeResponseDto {
    private String userId;
    private Set<String> permissions;
    private List<AuthzScopeSummaryDto> scopes;
    private boolean superAdmin;
    private List<ScopeSummaryDto> allowedScopes;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }

    public List<AuthzScopeSummaryDto> getScopes() {
        return scopes;
    }

    public void setScopes(List<AuthzScopeSummaryDto> scopes) {
        this.scopes = scopes;
    }

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    public void setSuperAdmin(boolean superAdmin) {
        this.superAdmin = superAdmin;
    }

    public List<ScopeSummaryDto> getAllowedScopes() {
        return allowedScopes;
    }

    public void setAllowedScopes(List<ScopeSummaryDto> allowedScopes) {
        this.allowedScopes = allowedScopes;
    }
}

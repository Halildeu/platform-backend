package com.example.variant.authz;

import java.util.List;

public class AuthzMeResponse {
    private String userId;
    private List<String> permissions;
    private List<String> roles;
    private List<ScopeSummaryDto> allowedScopes;
    private Boolean superAdmin;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public List<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(List<String> permissions) {
        this.permissions = permissions;
    }

    public List<String> getRoles() {
        return roles;
    }

    public void setRoles(List<String> roles) {
        this.roles = roles;
    }

    public List<ScopeSummaryDto> getAllowedScopes() {
        return allowedScopes;
    }

    public void setAllowedScopes(List<ScopeSummaryDto> allowedScopes) {
        this.allowedScopes = allowedScopes;
    }

    public Boolean getSuperAdmin() {
        return superAdmin;
    }

    public void setSuperAdmin(Boolean superAdmin) {
        this.superAdmin = superAdmin;
    }
}

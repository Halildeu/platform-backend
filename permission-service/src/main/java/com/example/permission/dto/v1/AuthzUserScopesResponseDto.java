package com.example.permission.dto.v1;

import java.util.List;

public class AuthzUserScopesResponseDto {
    private List<AuthzPermissionScopesDto> items;
    private int total;
    private List<ScopeSummaryDto> allowedScopes;
    private boolean superAdmin;

    public AuthzUserScopesResponseDto() {
    }

    public AuthzUserScopesResponseDto(List<AuthzPermissionScopesDto> items, int total) {
        this.items = items;
        this.total = total;
    }

    public AuthzUserScopesResponseDto(List<AuthzPermissionScopesDto> items, int total, List<ScopeSummaryDto> allowedScopes, boolean superAdmin) {
        this.items = items;
        this.total = total;
        this.allowedScopes = allowedScopes;
        this.superAdmin = superAdmin;
    }

    public List<AuthzPermissionScopesDto> getItems() {
        return items;
    }

    public void setItems(List<AuthzPermissionScopesDto> items) {
        this.items = items;
    }

    public int getTotal() {
        return total;
    }

    public void setTotal(int total) {
        this.total = total;
    }

    public List<ScopeSummaryDto> getAllowedScopes() {
        return allowedScopes;
    }

    public void setAllowedScopes(List<ScopeSummaryDto> allowedScopes) {
        this.allowedScopes = allowedScopes;
    }

    public boolean isSuperAdmin() {
        return superAdmin;
    }

    public void setSuperAdmin(boolean superAdmin) {
        this.superAdmin = superAdmin;
    }
}

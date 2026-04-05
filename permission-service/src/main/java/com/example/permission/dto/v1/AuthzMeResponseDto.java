package com.example.permission.dto.v1;

import java.util.List;
import java.util.Map;
import java.util.Set;

public class AuthzMeResponseDto {
    private String userId;
    private Set<String> permissions;        // legacy — backward compat
    private List<AuthzScopeSummaryDto> scopes;
    private boolean superAdmin;
    private List<ScopeSummaryDto> allowedScopes;

    // STORY-0318 additions
    private List<String> roles;
    private Map<String, String> modules;     // key → "VIEW" | "MANAGE"
    private Map<String, String> actions;     // key → "ALLOW" | "DENY"
    private Map<String, String> reports;     // key → "ALLOW" | "DENY"
    private Map<String, String> pages;       // key → "ALLOW" | "DENY"

    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }

    public Set<String> getPermissions() { return permissions; }
    public void setPermissions(Set<String> permissions) { this.permissions = permissions; }

    public List<AuthzScopeSummaryDto> getScopes() { return scopes; }
    public void setScopes(List<AuthzScopeSummaryDto> scopes) { this.scopes = scopes; }

    public boolean isSuperAdmin() { return superAdmin; }
    public void setSuperAdmin(boolean superAdmin) { this.superAdmin = superAdmin; }

    public List<ScopeSummaryDto> getAllowedScopes() { return allowedScopes; }
    public void setAllowedScopes(List<ScopeSummaryDto> allowedScopes) { this.allowedScopes = allowedScopes; }

    public List<String> getRoles() { return roles; }
    public void setRoles(List<String> roles) { this.roles = roles; }

    public Map<String, String> getModules() { return modules; }
    public void setModules(Map<String, String> modules) { this.modules = modules; }

    public Map<String, String> getActions() { return actions; }
    public void setActions(Map<String, String> actions) { this.actions = actions; }

    public Map<String, String> getReports() { return reports; }
    public void setReports(Map<String, String> reports) { this.reports = reports; }

    public Map<String, String> getPages() { return pages; }
    public void setPages(Map<String, String> pages) { this.pages = pages; }
}

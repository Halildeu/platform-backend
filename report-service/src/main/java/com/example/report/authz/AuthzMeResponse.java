package com.example.report.authz;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class AuthzMeResponse {
    private String userId;
    private List<String> permissions;
    private List<ScopeSummaryDto> allowedScopes;
    private Boolean superAdmin;
    private Map<String, String> reports;

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

    public Map<String, String> getReports() {
        return reports;
    }

    public void setReports(Map<String, String> reports) {
        this.reports = reports;
    }

    public boolean isSuperAdmin() {
        return Boolean.TRUE.equals(superAdmin);
    }

    /**
     * Check if user has ALLOW grant for a specific report group.
     * Returns false (deny-default) when report key is not in the map.
     * CNS-20260411-003 #3: deny-default for report access.
     */
    public boolean canViewReport(String reportKey) {
        if (reports == null || reports.isEmpty()) {
            return false;
        }
        return "ALLOW".equals(reports.get(reportKey));
    }

    public boolean hasPermission(String permission) {
        return permissions != null && permissions.contains(permission);
    }

    public Set<String> getScopeRefIds(String scopeType) {
        if (allowedScopes == null) {
            return Collections.emptySet();
        }
        return allowedScopes.stream()
                .filter(s -> scopeType.equalsIgnoreCase(s.getScopeType()))
                .map(ScopeSummaryDto::getScopeRefId)
                .collect(Collectors.toSet());
    }
}

package com.example.report.access;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.ReportDefinition;
import org.springframework.stereotype.Component;

@Component
public class ReportAccessEvaluator {

    public enum AccessResult {
        ALLOWED,
        DENIED_NO_REPORT_VIEW,
        DENIED_NO_REPORT_PERMISSION,
        DENIED_REPORT_GROUP
    }

    public AccessResult evaluate(ReportDefinition def, AuthzMeResponse authz) {
        if (authz == null) {
            return AccessResult.DENIED_NO_REPORT_VIEW;
        }

        if (authz.isSuperAdmin()) {
            return AccessResult.ALLOWED;
        }

        if (!authz.hasPermission("REPORT_VIEW")) {
            return AccessResult.DENIED_NO_REPORT_VIEW;
        }

        if (def.access() != null && def.access().permission() != null
                && !def.access().permission().isBlank()
                && !authz.hasPermission(def.access().permission())) {
            return AccessResult.DENIED_NO_REPORT_PERMISSION;
        }

        // CNS-20260411-003 #3: Report group deny-default via authz.reports map.
        // If reports map is populated, require explicit ALLOW for the report's category.
        if (authz.getReports() != null && !authz.getReports().isEmpty()
                && def.access() != null && def.access().reportGroup() != null
                && !def.access().reportGroup().isBlank()) {
            if (!authz.canViewReport(def.access().reportGroup())) {
                return AccessResult.DENIED_REPORT_GROUP;
            }
        }

        return AccessResult.ALLOWED;
    }

    public boolean canExport(AuthzMeResponse authz) {
        if (authz == null) {
            return false;
        }
        return authz.isSuperAdmin() || authz.hasPermission("REPORT_EXPORT");
    }
}

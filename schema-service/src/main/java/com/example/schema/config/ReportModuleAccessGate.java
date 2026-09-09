package com.example.schema.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;

import java.util.List;

/**
 * "May this user use the REPORT module?" — the API-side twin of the shell's
 * {@code requiredModule="REPORT"} route guard (gitops#3605 / #3608).
 *
 * <p>The answer must agree with what permission-service projects into
 * {@code /authz/me.modules.REPORT}, otherwise a user sees the Explorer in the
 * menu and gets 403 from every panel. That projection
 * ({@code AuthorizationControllerV1.applyReportModuleInvariant}) is:
 * <ol>
 *   <li>an explicit {@code module:REPORT} DENY wins;</li>
 *   <li>an explicit {@code module:REPORT} grant allows;</li>
 *   <li>otherwise any positive REPORT-type grant — which TupleSyncService writes
 *       as {@code report} / {@code report_group} tuples — surfaces the module.</li>
 * </ol>
 * Organization admins are allowed outright, as in report-service.
 *
 * <p>Fail-closed: when OpenFGA is enabled but cannot answer (circuit open,
 * transport error) the decision is DENY with the reason recorded. When OpenFGA
 * is disabled (local/dev) every check passes — {@code OpenFgaStartupGuard}
 * warns about that at startup and the k8s profile defaults it to enabled.
 */
public class ReportModuleAccessGate {

    public static final String MODULE = "REPORT";

    private static final List<String> DERIVED_OBJECT_TYPES = List.of("report", "report_group");

    private final OpenFgaAuthzService authz;

    public ReportModuleAccessGate(OpenFgaAuthzService authz) {
        this.authz = authz;
    }

    public record Decision(boolean allowed, String reason) {
        static Decision allow(String reason) {
            return new Decision(true, reason);
        }

        static Decision deny(String reason) {
            return new Decision(false, reason);
        }
    }

    public Decision decide(String userId) {
        if (userId == null || userId.isBlank()) {
            return Decision.deny("no_user_id");
        }
        if (!authz.isEnabled()) {
            return Decision.allow("openfga_disabled");
        }
        if (authz.check(userId, "admin", "organization", "default")) {
            return Decision.allow("org_admin");
        }
        if (authz.check(userId, "blocked", "module", MODULE)) {
            return Decision.deny("module_blocked");
        }
        if (authz.check(userId, "can_view", "module", MODULE)) {
            return Decision.allow("module_grant");
        }
        for (String type : DERIVED_OBJECT_TYPES) {
            var result = authz.listObjectsResult(userId, "can_view", type);
            if (!result.available()) {
                return Decision.deny("openfga_unavailable:" + result.reason());
            }
            if (!result.objectIds().isEmpty()) {
                return Decision.allow("derived:" + type);
            }
        }
        return Decision.deny("no_report_grant");
    }
}

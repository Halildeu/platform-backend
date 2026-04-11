package com.example.report.access;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ReportDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests for ReportAccessEvaluator deny-default semantics.
 * CNS-20260411-003 #3: report group deny-default via authz.reports map.
 */
class ReportAccessEvaluatorTest {

    private ReportAccessEvaluator evaluator;

    @BeforeEach
    void setUp() {
        evaluator = new ReportAccessEvaluator();
    }

    @Test
    void nullAuthz_returnsDenied() {
        var def = minimalReport("test-report", null);
        assertEquals(ReportAccessEvaluator.AccessResult.DENIED_NO_REPORT_VIEW,
                evaluator.evaluate(def, null));
    }

    @Test
    void superAdmin_alwaysAllowed() {
        var authz = authzWith(true, List.of(), Map.of());
        var def = minimalReport("test-report", "HR_REPORTS");
        assertEquals(ReportAccessEvaluator.AccessResult.ALLOWED,
                evaluator.evaluate(def, authz));
    }

    @Test
    void noReportViewPermission_denied() {
        var authz = authzWith(false, List.of(), Map.of());
        var def = minimalReport("test-report", null);
        assertEquals(ReportAccessEvaluator.AccessResult.DENIED_NO_REPORT_VIEW,
                evaluator.evaluate(def, authz));
    }

    @Test
    void reportGroupAllow_allowed() {
        var authz = authzWith(false, List.of("REPORT_VIEW"), Map.of("HR_REPORTS", "ALLOW"));
        var def = minimalReport("hr-report", "HR_REPORTS");
        assertEquals(ReportAccessEvaluator.AccessResult.ALLOWED,
                evaluator.evaluate(def, authz));
    }

    @Test
    void reportGroupDeny_denied() {
        var authz = authzWith(false, List.of("REPORT_VIEW"), Map.of("HR_REPORTS", "DENY"));
        var def = minimalReport("hr-report", "HR_REPORTS");
        assertEquals(ReportAccessEvaluator.AccessResult.DENIED_REPORT_GROUP,
                evaluator.evaluate(def, authz));
    }

    @Test
    void reportGroupMissing_denyDefault() {
        var authz = authzWith(false, List.of("REPORT_VIEW"),
                Map.of("FINANCE_REPORTS", "ALLOW"));
        var def = minimalReport("hr-report", "HR_REPORTS");
        assertEquals(ReportAccessEvaluator.AccessResult.DENIED_REPORT_GROUP,
                evaluator.evaluate(def, authz));
    }

    @Test
    void noReportGroup_noReportsMap_allowed() {
        var authz = authzWith(false, List.of("REPORT_VIEW"), null);
        var def = minimalReport("generic-report", null);
        assertEquals(ReportAccessEvaluator.AccessResult.ALLOWED,
                evaluator.evaluate(def, authz));
    }

    @Test
    void emptyReportsMap_noReportGroup_allowed() {
        var authz = authzWith(false, List.of("REPORT_VIEW"), Map.of());
        var def = minimalReport("generic-report", null);
        assertEquals(ReportAccessEvaluator.AccessResult.ALLOWED,
                evaluator.evaluate(def, authz));
    }

    @Test
    void canExport_superAdmin_true() {
        var authz = authzWith(true, List.of(), null);
        assertEquals(true, evaluator.canExport(authz));
    }

    @Test
    void canExport_withPermission_true() {
        var authz = authzWith(false, List.of("REPORT_EXPORT"), null);
        assertEquals(true, evaluator.canExport(authz));
    }

    @Test
    void canExport_noPermission_false() {
        var authz = authzWith(false, List.of("REPORT_VIEW"), null);
        assertEquals(false, evaluator.canExport(authz));
    }

    private static AuthzMeResponse authzWith(boolean superAdmin,
                                              List<String> permissions,
                                              Map<String, String> reports) {
        var authz = new AuthzMeResponse();
        authz.setSuperAdmin(superAdmin);
        authz.setPermissions(permissions);
        authz.setReports(reports);
        return authz;
    }

    private static ReportDefinition minimalReport(String key, String reportGroup) {
        return new ReportDefinition(
                key, "1.0", "Test Report", "desc", "general",
                "test_table", "dbo", "static", null, null,
                List.of(new com.example.report.registry.ColumnDefinition(
                        "id", "ID", "number", 100, false)),
                "id", "ASC",
                new AccessConfig(null, reportGroup, null, null)
        );
    }
}

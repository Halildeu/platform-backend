package com.example.report.authz;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for AuthzMeResponse — permission checks, report group access, scope.
 * SK-7 coverage target.
 */
class AuthzMeResponseTest {

    @Test
    void isSuperAdmin_true() {
        var authz = build(true, List.of(), null);
        assertTrue(authz.isSuperAdmin());
    }

    @Test
    void isSuperAdmin_false() {
        var authz = build(false, List.of(), null);
        assertFalse(authz.isSuperAdmin());
    }

    @Test
    void isSuperAdmin_null_returnsFalse() {
        var authz = new AuthzMeResponse();
        assertFalse(authz.isSuperAdmin());
    }

    @Test
    void hasPermission_exists_returnsTrue() {
        var authz = build(false, List.of("REPORT_VIEW", "REPORT_EXPORT"), null);
        assertTrue(authz.hasPermission("REPORT_VIEW"));
    }

    @Test
    void hasPermission_missing_returnsFalse() {
        var authz = build(false, List.of("REPORT_VIEW"), null);
        assertFalse(authz.hasPermission("ADMIN"));
    }

    @Test
    void hasPermission_nullPerms_returnsFalse() {
        var authz = new AuthzMeResponse();
        assertFalse(authz.hasPermission("X"));
    }

    @Test
    void canViewReport_allow_returnsTrue() {
        var authz = build(false, List.of(), Map.of("HR_REPORTS", "ALLOW"));
        assertTrue(authz.canViewReport("HR_REPORTS"));
    }

    @Test
    void canViewReport_deny_returnsFalse() {
        var authz = build(false, List.of(), Map.of("HR_REPORTS", "DENY"));
        assertFalse(authz.canViewReport("HR_REPORTS"));
    }

    @Test
    void canViewReport_missing_denyDefault() {
        var authz = build(false, List.of(), Map.of("FINANCE_REPORTS", "ALLOW"));
        assertFalse(authz.canViewReport("HR_REPORTS"));
    }

    @Test
    void canViewReport_nullMap_returnsFalse() {
        var authz = build(false, List.of(), null);
        assertFalse(authz.canViewReport("HR_REPORTS"));
    }

    @Test
    void canViewReport_emptyMap_returnsFalse() {
        var authz = build(false, List.of(), Map.of());
        assertFalse(authz.canViewReport("HR_REPORTS"));
    }

    @Test
    void getScopeRefIds_filtersByType() {
        var authz = new AuthzMeResponse();
        var scopes = List.of(
                scopeDto("COMPANY", "C1"),
                scopeDto("COMPANY", "C2"),
                scopeDto("PROJECT", "P1"));
        authz.setAllowedScopes(scopes);

        var companyIds = authz.getScopeRefIds("COMPANY");
        assertEquals(2, companyIds.size());
        assertTrue(companyIds.contains("C1"));

        var projectIds = authz.getScopeRefIds("PROJECT");
        assertEquals(1, projectIds.size());
    }

    @Test
    void getScopeRefIds_nullScopes_returnsEmpty() {
        var authz = new AuthzMeResponse();
        assertTrue(authz.getScopeRefIds("COMPANY").isEmpty());
    }

    private static AuthzMeResponse build(boolean superAdmin, List<String> perms, Map<String, String> reports) {
        var authz = new AuthzMeResponse();
        authz.setSuperAdmin(superAdmin);
        authz.setPermissions(perms);
        authz.setReports(reports);
        authz.setUserId("user1");
        return authz;
    }

    private static ScopeSummaryDto scopeDto(String type, String refId) {
        var dto = new ScopeSummaryDto();
        dto.setScopeType(type);
        dto.setScopeRefId(refId);
        return dto;
    }
}

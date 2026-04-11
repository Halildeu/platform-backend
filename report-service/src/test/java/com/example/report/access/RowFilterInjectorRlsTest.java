package com.example.report.access;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.ScopeSummaryDto;
import com.example.report.registry.AccessConfig;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RowFilterInjectorRlsTest {

    private RowFilterInjector injector;

    @BeforeEach
    void setUp() {
        injector = new RowFilterInjector();
    }

    // ---------- helpers ----------

    private static ReportDefinition defWithRowFilter(String column, String scopeType, String bypassPermission) {
        AccessConfig.RowFilter rf = new AccessConfig.RowFilter(column, scopeType, bypassPermission);
        AccessConfig access = new AccessConfig(null, null, null, rf);
        return new ReportDefinition(
                "test-report", "1.0", "Test", "", "test",
                "MY_TABLE", "dbo", "static", null, null,
                List.of(new ColumnDefinition("_id", "_id", "number", 1, false)),
                "_id", "ASC", access);
    }

    private static ReportDefinition defWithoutAccess() {
        return new ReportDefinition(
                "no-access-report", "1.0", "No Access", "", "test",
                "MY_TABLE", "dbo", "static", null, null,
                List.of(new ColumnDefinition("_id", "_id", "number", 1, false)),
                "_id", "ASC", null);
    }

    private static AuthzMeResponse normalUser(List<ScopeSummaryDto> scopes) {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("user-123");
        authz.setSuperAdmin(false);
        authz.setPermissions(List.of());
        authz.setAllowedScopes(scopes);
        return authz;
    }

    private static AuthzMeResponse superAdmin() {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("admin-001");
        authz.setSuperAdmin(true);
        authz.setPermissions(List.of());
        authz.setAllowedScopes(List.of());
        return authz;
    }

    private static AuthzMeResponse userWithPermission(String permission) {
        AuthzMeResponse authz = new AuthzMeResponse();
        authz.setUserId("user-bp");
        authz.setSuperAdmin(false);
        authz.setPermissions(List.of(permission));
        authz.setAllowedScopes(List.of());
        return authz;
    }

    // ---------- tests ----------

    @Nested
    @DisplayName("Normal user with COMPANY scope")
    class NormalUserCompanyScope {

        @Test
        @DisplayName("produces WHERE clause with company_id IN for allowed companies 1 and 5")
        void companyFilter_producesInClause() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1"),
                    new ScopeSummaryDto("COMPANY", "5")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNotNull(result.whereClause(), "WHERE clause must not be null for scoped user");
            assertTrue(result.whereClause().contains("[company_id] IN (:_rlsIds)"),
                    "WHERE clause must use column IN (:_rlsIds) pattern, got: " + result.whereClause());

            assertNotNull(result.params(), "Params must not be null");
            @SuppressWarnings("unchecked")
            Set<String> rlsIds = (Set<String>) result.params().getValue("_rlsIds");
            assertNotNull(rlsIds, "_rlsIds parameter must exist");
            assertEquals(2, rlsIds.size(), "Should contain exactly 2 allowed company IDs");
            assertTrue(rlsIds.contains("1"), "Should contain company ID 1");
            assertTrue(rlsIds.contains("5"), "Should contain company ID 5");
        }

        @Test
        @DisplayName("single company scope produces single-element set")
        void singleCompany_producesCorrectSet() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "42")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            @SuppressWarnings("unchecked")
            Set<String> rlsIds = (Set<String>) result.params().getValue("_rlsIds");
            assertEquals(1, rlsIds.size());
            assertTrue(rlsIds.contains("42"));
        }
    }

    @Nested
    @DisplayName("SuperAdmin bypass")
    class SuperAdminBypass {

        @Test
        @DisplayName("superAdmin gets null WHERE clause regardless of row filter config")
        void superAdmin_bypassesRls() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = superAdmin();

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNull(result.whereClause(), "SuperAdmin must get null WHERE clause (full bypass)");
            assertNull(result.params(), "SuperAdmin must get null params (full bypass)");
        }

        @Test
        @DisplayName("superAdmin bypass works even when scopes list is empty")
        void superAdmin_bypassEvenWithNoScopes() {
            ReportDefinition def = defWithRowFilter("project_id", "PROJECT", null);
            AuthzMeResponse authz = superAdmin();

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNull(result.whereClause());
            assertNull(result.params());
        }
    }

    @Nested
    @DisplayName("Unauthorized company exclusion")
    class UnauthorizedCompanyExclusion {

        @Test
        @DisplayName("user with companies 1,5 does not get company 99 in filter")
        void unauthorizedCompany_excludedFromFilter() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1"),
                    new ScopeSummaryDto("COMPANY", "5")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            @SuppressWarnings("unchecked")
            Set<String> rlsIds = (Set<String>) result.params().getValue("_rlsIds");
            assertFalse(rlsIds.contains("99"),
                    "Company 99 must NOT be in the allowed set");
            assertFalse(rlsIds.contains("2"),
                    "Company 2 must NOT be in the allowed set");
        }

        @Test
        @DisplayName("mixed scope types: only matching scopeType is included")
        void mixedScopeTypes_onlyMatchingIncluded() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1"),
                    new ScopeSummaryDto("PROJECT", "10"),
                    new ScopeSummaryDto("COMPANY", "5"),
                    new ScopeSummaryDto("PROJECT", "20")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            @SuppressWarnings("unchecked")
            Set<String> rlsIds = (Set<String>) result.params().getValue("_rlsIds");
            assertEquals(2, rlsIds.size(), "Only COMPANY scopes should be included");
            assertTrue(rlsIds.contains("1"));
            assertTrue(rlsIds.contains("5"));
            assertFalse(rlsIds.contains("10"), "PROJECT scope should not leak into COMPANY filter");
            assertFalse(rlsIds.contains("20"), "PROJECT scope should not leak into COMPANY filter");
        }
    }

    @Nested
    @DisplayName("Empty scope (deny-all)")
    class EmptyScope {

        @Test
        @DisplayName("user with no scopes gets deny-all WHERE clause (1=0)")
        void emptyScopes_denyAll() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of());

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertEquals("1=0", result.whereClause(),
                    "Empty scope must produce deny-all clause '1=0'");
        }

        @Test
        @DisplayName("user with scopes of different type gets deny-all for unmatched scopeType")
        void wrongScopeType_denyAll() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("PROJECT", "10"),
                    new ScopeSummaryDto("PROJECT", "20")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertEquals("1=0", result.whereClause(),
                    "No matching COMPANY scope should result in deny-all '1=0'");
        }

        @Test
        @DisplayName("null authz gets deny-all WHERE clause (1=0)")
        void nullAuthz_denyAll() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, null);

            assertEquals("1=0", result.whereClause(),
                    "Null authz must produce deny-all clause '1=0' (fail-closed)");
        }
    }

    @Nested
    @DisplayName("Polymorphic scope (PROJECT vs COMPANY)")
    class PolymorphicScope {

        @Test
        @DisplayName("PROJECT scopeType uses project_id column filter")
        void projectScope_usesProjectColumn() {
            ReportDefinition def = defWithRowFilter("project_id", "PROJECT", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("PROJECT", "10"),
                    new ScopeSummaryDto("PROJECT", "20"),
                    new ScopeSummaryDto("COMPANY", "1")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNotNull(result.whereClause());
            assertTrue(result.whereClause().contains("[project_id] IN (:_rlsIds)"),
                    "Should use project_id column, got: " + result.whereClause());

            @SuppressWarnings("unchecked")
            Set<String> rlsIds = (Set<String>) result.params().getValue("_rlsIds");
            assertEquals(2, rlsIds.size(), "Should contain only PROJECT scope IDs");
            assertTrue(rlsIds.contains("10"));
            assertTrue(rlsIds.contains("20"));
            assertFalse(rlsIds.contains("1"), "COMPANY scope should not leak into PROJECT filter");
        }

        @Test
        @DisplayName("COMPANY scopeType uses company_id column filter")
        void companyScope_usesCompanyColumn() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1"),
                    new ScopeSummaryDto("COMPANY", "3"),
                    new ScopeSummaryDto("PROJECT", "10")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNotNull(result.whereClause());
            assertTrue(result.whereClause().contains("[company_id] IN (:_rlsIds)"),
                    "Should use company_id column, got: " + result.whereClause());

            @SuppressWarnings("unchecked")
            Set<String> rlsIds = (Set<String>) result.params().getValue("_rlsIds");
            assertEquals(2, rlsIds.size(), "Should contain only COMPANY scope IDs");
            assertTrue(rlsIds.contains("1"));
            assertTrue(rlsIds.contains("3"));
            assertFalse(rlsIds.contains("10"), "PROJECT scope should not leak into COMPANY filter");
        }

        @Test
        @DisplayName("scope type matching is case-insensitive")
        void scopeTypeMatch_caseInsensitive() {
            ReportDefinition def = defWithRowFilter("project_id", "PROJECT", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("project", "10"),
                    new ScopeSummaryDto("Project", "20")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNotNull(result.whereClause(), "Case-insensitive match should find scopes");
            @SuppressWarnings("unchecked")
            Set<String> rlsIds = (Set<String>) result.params().getValue("_rlsIds");
            assertEquals(2, rlsIds.size(), "Case-insensitive match should include both scopes");
        }
    }

    @Nested
    @DisplayName("Bypass permission")
    class BypassPermission {

        @Test
        @DisplayName("user with bypass permission gets null clause (full access)")
        void bypassPermission_fullAccess() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", "report:all-companies");
            AuthzMeResponse authz = userWithPermission("report:all-companies");

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNull(result.whereClause(),
                    "User with bypass permission should get null WHERE (full access)");
            assertNull(result.params());
        }

        @Test
        @DisplayName("user without bypass permission still gets scoped filter")
        void noBypassPermission_scopedFilter() {
            ReportDefinition def = defWithRowFilter("company_id", "COMPANY", "report:all-companies");
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNotNull(result.whereClause(), "Without bypass permission, WHERE clause must be present");
            assertTrue(result.whereClause().contains("[company_id] IN (:_rlsIds)"));
        }
    }

    @Nested
    @DisplayName("Edge cases - no row filter configured")
    class NoRowFilterConfigured {

        @Test
        @DisplayName("report with no access config returns null clause")
        void noAccessConfig_nullClause() {
            ReportDefinition def = defWithoutAccess();
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNull(result.whereClause(), "No access config should produce null clause (open)");
        }

        @Test
        @DisplayName("report with access but null row filter returns null clause")
        void accessWithoutRowFilter_nullClause() {
            AccessConfig access = new AccessConfig("read:report", null, null, null);
            ReportDefinition def = new ReportDefinition(
                    "test-no-rf", "1.0", "Test", "", "test",
                    "MY_TABLE", "dbo", "static", null, null,
                    List.of(new ColumnDefinition("_id", "_id", "number", 1, false)),
                    "_id", "ASC", access);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNull(result.whereClause(), "Null row filter should produce null clause (open)");
        }

        @Test
        @DisplayName("row filter with null scopeType returns null clause")
        void nullScopeType_nullClause() {
            ReportDefinition def = defWithRowFilter("company_id", null, null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNull(result.whereClause(), "Null scopeType should produce null clause");
        }

        @Test
        @DisplayName("row filter with null column returns null clause")
        void nullColumn_nullClause() {
            ReportDefinition def = defWithRowFilter(null, "COMPANY", null);
            AuthzMeResponse authz = normalUser(List.of(
                    new ScopeSummaryDto("COMPANY", "1")
            ));

            RowFilterInjector.RlsResult result = injector.buildRlsClause(def, authz);

            assertNull(result.whereClause(), "Null column should produce null clause");
        }
    }
}

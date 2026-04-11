package com.example.permission.service;

import com.example.permission.model.GrantType;
import com.example.permission.model.PermissionType;
import com.example.permission.model.Role;
import com.example.permission.model.RolePermission;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TupleSyncService grant resolution and mapping logic.
 * CNS-20260411-005: can_edit relation + deny-wins validation.
 */
class TupleSyncMappingTest {

    /**
     * Create a minimal TupleSyncService for testing resolveEffectiveGrants.
     * We only test the pure mapping logic — no OpenFGA calls needed.
     */
    private TupleSyncService createServiceForMappingTests() {
        // TupleSyncService constructor requires non-null deps, but resolveEffectiveGrants
        // is a pure function that only reads from its List<RolePermission> parameter.
        // We use a real instance with null deps (only for mapping tests).
        return new TupleSyncService(null, null, null, null, null);
    }

    private RolePermission createPerm(String roleName, PermissionType type, String key, GrantType grant) {
        var role = new Role();
        role.setName(roleName);
        var rp = new RolePermission();
        rp.setRole(role);
        rp.setPermissionType(type);
        rp.setPermissionKey(key);
        rp.setGrantType(grant);
        return rp;
    }

    @Nested
    @DisplayName("resolveEffectiveGrants — deny-wins semantics")
    class DenyWins {

        @Test
        void deny_wins_over_manage() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Editor", PermissionType.REPORT, "HR_REPORTS", GrantType.MANAGE),
                    createPerm("Restricted", PermissionType.REPORT, "HR_REPORTS", GrantType.DENY)
            );
            Map<String, TupleSyncService.ResolvedGrant> result = svc.resolveEffectiveGrants(perms);
            var grant = result.get("REPORT:HR_REPORTS");
            assertNotNull(grant);
            assertEquals(GrantType.DENY, grant.grantType());
        }

        @Test
        void deny_wins_over_view() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Viewer", PermissionType.REPORT, "FINANCE_REPORTS", GrantType.VIEW),
                    createPerm("Blocked", PermissionType.REPORT, "FINANCE_REPORTS", GrantType.DENY)
            );
            var result = svc.resolveEffectiveGrants(perms);
            assertEquals(GrantType.DENY, result.get("REPORT:FINANCE_REPORTS").grantType());
        }

        @Test
        void manage_wins_over_view() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Viewer", PermissionType.REPORT, "HR_REPORTS", GrantType.VIEW),
                    createPerm("Editor", PermissionType.REPORT, "HR_REPORTS", GrantType.MANAGE)
            );
            var result = svc.resolveEffectiveGrants(perms);
            assertEquals(GrantType.MANAGE, result.get("REPORT:HR_REPORTS").grantType());
        }

        @Test
        void single_view_stays_view() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Viewer", PermissionType.REPORT, "HR_REPORTS", GrantType.VIEW)
            );
            var result = svc.resolveEffectiveGrants(perms);
            assertEquals(GrantType.VIEW, result.get("REPORT:HR_REPORTS").grantType());
        }
    }

    @Nested
    @DisplayName("REPORT MANAGE → can_edit mapping (CNS-20260411-005)")
    class ReportCanEdit {

        @Test
        void report_manage_resolves_to_manage_grant() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Editor", PermissionType.REPORT, "HR_REPORTS", GrantType.MANAGE)
            );
            var result = svc.resolveEffectiveGrants(perms);
            assertEquals(GrantType.MANAGE, result.get("REPORT:HR_REPORTS").grantType());
            // This MANAGE grant will map to can_edit in toTupleMapping
        }

        @Test
        void report_allow_resolves_to_allow_grant() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Reporter", PermissionType.REPORT, "SALES_REPORTS", GrantType.ALLOW)
            );
            var result = svc.resolveEffectiveGrants(perms);
            assertEquals(GrantType.ALLOW, result.get("REPORT:SALES_REPORTS").grantType());
            // This ALLOW grant will map to can_view in toTupleMapping
        }
    }

    @Nested
    @DisplayName("MODULE permission resolution")
    class ModuleResolution {

        @Test
        void module_manage_wins_over_view() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Viewer", PermissionType.MODULE, "AUDIT", GrantType.VIEW),
                    createPerm("Admin", PermissionType.MODULE, "AUDIT", GrantType.MANAGE)
            );
            var result = svc.resolveEffectiveGrants(perms);
            assertEquals(GrantType.MANAGE, result.get("MODULE:AUDIT").grantType());
        }

        @Test
        void module_deny_wins_over_manage() {
            var svc = createServiceForMappingTests();
            var perms = List.of(
                    createPerm("Admin", PermissionType.MODULE, "ACCESS", GrantType.MANAGE),
                    createPerm("Restricted", PermissionType.MODULE, "ACCESS", GrantType.DENY)
            );
            var result = svc.resolveEffectiveGrants(perms);
            assertEquals(GrantType.DENY, result.get("MODULE:ACCESS").grantType());
        }
    }

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        void null_fields_skipped() {
            var svc = createServiceForMappingTests();
            var rp = new RolePermission();
            // All fields null — should be skipped
            var result = svc.resolveEffectiveGrants(List.of(rp));
            assertTrue(result.isEmpty());
        }

        // TB-21: deprecated_page_field_skipped test removed — PAGE/FIELD enum deleted

        @Test
        void empty_list_returns_empty() {
            var svc = createServiceForMappingTests();
            var result = svc.resolveEffectiveGrants(List.of());
            assertTrue(result.isEmpty());
        }
    }
}

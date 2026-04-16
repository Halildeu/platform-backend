package com.example.report.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.example.report.authz.AuthzMeResponse;
import com.example.report.authz.PermissionResolver;
import com.example.report.query.DashboardQueryEngine;
import com.example.report.registry.DashboardDefinition;
import com.example.report.registry.DashboardRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.server.ResponseStatusException;

/**
 * PR6c-1 — Dashboard authorization tests (previously uncovered).
 *
 * <p>Dashboard endpoints gate on {@code REPORT_VIEW} plus optional
 * {@code def.access().permission()} metadata. Super-admin bypasses both.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DashboardControllerAuthzTest {

    @Mock private PermissionResolver permissionResolver;
    @Mock private DashboardRegistry registry;
    @Mock private DashboardQueryEngine queryEngine;

    private DashboardController controller;

    @BeforeEach
    void setUp() {
        controller = new DashboardController(registry, permissionResolver, queryEngine);
    }

    // ---- list ------------------------------------------------------------

    @Test
    void listDashboards_superAdmin_seesAll() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.getAll()).thenReturn(List.of(
                dashboard("hr-analytics", null),
                dashboard("fin-analytics", null)));

        var response = controller.listDashboards(testJwt("admin"));

        assertEquals(200, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals(2, response.getBody().size());
    }

    @Test
    void listDashboards_withoutReportView_emptyList() {
        AuthzMeResponse authz = authzWith(false, List.of());  // no REPORT_VIEW
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.getAll()).thenReturn(List.of(dashboard("any", null)));

        var response = controller.listDashboards(testJwt("user1"));

        assertTrue(response.getBody().isEmpty());
    }

    @Test
    void listDashboards_reportViewOnly_filteredByPerDashboardPermission() {
        // User has REPORT_VIEW + one specific dashboard permission
        AuthzMeResponse authz = authzWith(false,
                List.of("REPORT_VIEW", "dashboards.fin-analytics.view"));
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.getAll()).thenReturn(List.of(
                dashboard("fin-analytics", "dashboards.fin-analytics.view"),
                dashboard("hr-analytics",  "dashboards.hr-analytics.view")));

        var response = controller.listDashboards(testJwt("user1"));

        assertNotNull(response.getBody());
        assertEquals(1, response.getBody().size(), "only fin-analytics should be visible");
        assertEquals("fin-analytics", response.getBody().get(0).key());
    }

    @Test
    void listDashboards_dashboardWithoutPermission_allowedForAnyReportViewUser() {
        // def.access().permission() blank/null → REPORT_VIEW alone is sufficient
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"));
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.getAll()).thenReturn(List.of(dashboard("open-dash", null)));

        var response = controller.listDashboards(testJwt("user1"));

        assertEquals(1, response.getBody().size());
    }

    // ---- metadata --------------------------------------------------------

    @Test
    void getMetadata_nonExistentDashboard_404() {
        when(permissionResolver.getAuthzMe(any())).thenReturn(authzWith(true, List.of()));
        when(registry.get("ghost")).thenReturn(Optional.empty());

        assertThrows(ResponseStatusException.class, () ->
                controller.getMetadata("ghost", testJwt("admin")));
    }

    @Test
    void getMetadata_reportViewButNoDashboardPermission_403() {
        AuthzMeResponse authz = authzWith(false, List.of("REPORT_VIEW"));
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("fin-analytics")).thenReturn(Optional.of(
                dashboard("fin-analytics", "dashboards.fin-analytics.view")));

        assertThrows(ResponseStatusException.class, () ->
                controller.getMetadata("fin-analytics", testJwt("user1")));
    }

    @Test
    void getMetadata_superAdmin_200() {
        AuthzMeResponse authz = authzWith(true, List.of());
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("fin-analytics")).thenReturn(Optional.of(
                dashboard("fin-analytics", "dashboards.fin-analytics.view")));

        var response = controller.getMetadata("fin-analytics", testJwt("admin"));
        assertEquals(200, response.getStatusCode().value());
    }

    // ---- kpis / charts (time-range validation) ----------------------------

    @Test
    void getKpis_noReportView_403() {
        AuthzMeResponse authz = authzWith(false, List.of());  // no REPORT_VIEW
        when(permissionResolver.getAuthzMe(any())).thenReturn(authz);
        when(registry.get("hr-analytics")).thenReturn(Optional.of(
                dashboard("hr-analytics", null)));

        assertThrows(ResponseStatusException.class, () ->
                controller.getKpis("hr-analytics", "30d", null, null, null, null, testJwt("user1")));
    }

    // ---- helpers ---------------------------------------------------------

    private static DashboardDefinition dashboard(String key, String permission) {
        var access = permission != null
                ? new com.example.report.registry.AccessConfig(permission, null, null, null)
                : null;
        return new DashboardDefinition(
                "dashboard",
                key,
                "1",
                "Dashboard " + key,
                "desc",
                "category",
                "icon",
                access,
                List.of("7d", "30d", "90d"),
                "30d",
                List.of(),
                List.of(),
                new com.example.report.registry.LayoutConfig(List.of()),
                java.util.Map.of());
    }

    private static AuthzMeResponse authzWith(boolean superAdmin, List<String> permissions) {
        var authz = new AuthzMeResponse();
        authz.setSuperAdmin(superAdmin);
        authz.setPermissions(permissions);
        authz.setUserId("test-user");
        return authz;
    }

    private static Jwt testJwt(String username) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("preferred_username", username)
                .claim("sub", username)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}

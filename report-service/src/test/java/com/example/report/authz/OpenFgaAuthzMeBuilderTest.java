package com.example.report.authz;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.AuthenticatedUserLookupService;
import com.example.commonauth.AuthenticatedUserLookupService.ResolvedAuthenticatedUser;
import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.scope.AuthzVersionProvider;
import com.example.commonauth.scope.ScopeContext;
import com.example.commonauth.scope.ScopeContextHolder;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Unit tests for {@link OpenFgaAuthzMeBuilder} — the Zanzibar-backed
 * {@code AuthzMeResponse} builder that replaces the legacy HTTP snapshot.
 *
 * <p>Verifies:
 * <ul>
 *   <li>jwt null / user lookup miss → empty response (fail-closed)</li>
 *   <li>super-admin short-circuit: flag set, permissions/scopes empty</li>
 *   <li>Non-admin: module/dashboard/scope-marker/column up-front probe</li>
 *   <li>Report-level: listObjects bulk, populates both reports map and legacy
 *       permission code strings</li>
 *   <li>allowedScopes sourced from ScopeContextHolder (set by ScopeContextFilter)</li>
 *   <li>Cache hit by (userId, authzVersion) returns the same instance</li>
 *   <li>Cache miss on version bump triggers a fresh rebuild</li>
 * </ul>
 */
class OpenFgaAuthzMeBuilderTest {

    private OpenFgaAuthzService openFga;
    private AuthenticatedUserLookupService userLookup;
    private AuthzVersionProvider versionProvider;
    private PermissionCodeToTupleMapper mapper;
    private OpenFgaAuthzMeBuilder builder;

    @BeforeEach
    void setUp() {
        openFga = mock(OpenFgaAuthzService.class);
        userLookup = mock(AuthenticatedUserLookupService.class);
        versionProvider = mock(AuthzVersionProvider.class);
        mapper = new PermissionCodeToTupleMapper();  // real — tests the alias registry
        builder = new OpenFgaAuthzMeBuilder(openFga, userLookup, mapper, versionProvider, 30, 1000);
        ScopeContextHolder.clear();
    }

    @AfterEach
    void tearDown() {
        ScopeContextHolder.clear();
    }

    // ---- Fail-closed paths ------------------------------------------------

    @Test
    void nullJwt_returnsEmptyFailClosedResponse() {
        AuthzMeResponse r = builder.getAuthzMe(null);

        assertNotNull(r);
        assertFalse(r.isSuperAdmin());
        assertTrue(r.getPermissions().isEmpty());
        assertTrue(r.getReports().isEmpty());
        assertTrue(r.getAllowedScopes().isEmpty());
    }

    @Test
    void userLookupMiss_returnsEmptyResponse() {
        Jwt jwt = testJwt("kc-uuid-12345");
        when(userLookup.resolve(any())).thenReturn(new ResolvedAuthenticatedUser(null, null, null));

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertFalse(r.isSuperAdmin());
        assertTrue(r.getPermissions().isEmpty());
        // Must NOT leak the raw subject as userId when lookup failed.
        assertEquals(null, r.getUserId());
    }

    // ---- Super-admin -------------------------------------------------------

    @Test
    void superAdmin_shortCircuitsPermissionsAndScopes() {
        Jwt jwt = stubLookup("42");
        when(versionProvider.getCurrentVersion()).thenReturn(7L);
        when(openFga.check(eq("42"), eq("admin"), eq("organization"), eq("default"))).thenReturn(true);

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertTrue(r.isSuperAdmin());
        assertEquals("42", r.getUserId());
        assertTrue(r.getPermissions().isEmpty(), "super-admin snapshot keeps permissions empty — isSuperAdmin() gates access");
        assertTrue(r.getReports().isEmpty());
        assertTrue(r.getAllowedScopes().isEmpty());
        // listObjects must NOT be called on the super-admin fast path.
        verify(openFga, times(0)).listObjects(anyString(), anyString(), anyString());
    }

    // ---- Non-admin: static permission probes -------------------------------

    @Test
    void nonAdmin_modulePermissionsProbed() {
        Jwt jwt = stubLookup("99");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        // Not super-admin
        when(openFga.check(eq("99"), eq("admin"), eq("organization"), eq("default"))).thenReturn(false);
        // Module-level: REPORT_VIEW allowed, REPORT_MANAGE denied, REPORT_EXPORT denied
        when(openFga.check(eq("99"), eq("can_view"), eq("module"), eq("REPORT"))).thenReturn(true);
        when(openFga.check(eq("99"), eq("can_manage"), eq("module"), eq("REPORT"))).thenReturn(false);
        when(openFga.check(eq("99"), eq("allowed"), eq("action"), eq("REPORT_EXPORT"))).thenReturn(false);
        when(openFga.listObjects(eq("99"), eq("can_view"), eq("report"))).thenReturn(List.of());

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertFalse(r.isSuperAdmin());
        assertTrue(r.getPermissions().contains("REPORT_VIEW"));
        assertFalse(r.getPermissions().contains("REPORT_MANAGE"));
        assertFalse(r.getPermissions().contains("REPORT_EXPORT"));
    }

    @Test
    void nonAdmin_reportListObjectsPopulatesMapAndLegacyCodes() {
        Jwt jwt = stubLookup("123");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        when(openFga.check(any(), any(), any(), any())).thenReturn(false);  // nothing else true
        when(openFga.listObjects(eq("123"), eq("can_view"), eq("report")))
                .thenReturn(List.of("SATIS_OZET", "HR_PERSONEL_LISTESI"));

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertTrue(r.getReports().containsKey("SATIS_OZET"));
        assertEquals("ALLOW", r.getReports().get("SATIS_OZET"));
        assertTrue(r.getReports().containsKey("HR_PERSONEL_LISTESI"));
        // Legacy permission code must be populated so hasPermission() works.
        assertTrue(r.getPermissions().contains("reports.satis-ozet.view"));
        assertTrue(r.getPermissions().contains("reports.hr-personel-listesi.view"));
        // Dashboard form is also populated (shared bucket).
        assertTrue(r.getPermissions().contains("dashboards.satis-ozet.view"));
    }

    @Test
    void nonAdmin_scopeMarkerProbed() {
        Jwt jwt = stubLookup("55");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        when(openFga.check(any(), any(), any(), any())).thenReturn(false);
        when(openFga.check(eq("55"), eq("can_view"), eq("report"), eq("HR_ALL_COMPANIES"))).thenReturn(true);
        when(openFga.listObjects(eq("55"), eq("can_view"), eq("report"))).thenReturn(List.of());

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertTrue(r.getPermissions().contains("reports.hr.all-companies"));
    }

    @Test
    void nonAdmin_dashboardOutlierProbed() {
        Jwt jwt = stubLookup("77");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        when(openFga.check(any(), any(), any(), any())).thenReturn(false);
        when(openFga.check(eq("77"), eq("can_view"), eq("report"), eq("FIN_ANALYTICS"))).thenReturn(true);
        when(openFga.listObjects(any(), any(), any())).thenReturn(List.of());

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertTrue(r.getPermissions().contains("dashboards.fin-analytics.view"));
    }

    @Test
    void nonAdmin_columnPermissionProbed() {
        Jwt jwt = stubLookup("88");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        when(openFga.check(any(), any(), any(), any())).thenReturn(false);
        when(openFga.check(eq("88"), eq("allowed"), eq("action"), eq("HR_SALARY_VIEW"))).thenReturn(true);
        when(openFga.listObjects(any(), any(), any())).thenReturn(List.of());

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertTrue(r.getPermissions().contains("reports.hr.salary-view"));
    }

    // ---- allowedScopes ----------------------------------------------------

    @Test
    void allowedScopes_populatedFromScopeContextHolder() {
        Jwt jwt = stubLookup("200");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        when(openFga.check(any(), any(), any(), any())).thenReturn(false);
        when(openFga.listObjects(any(), any(), any())).thenReturn(List.of());
        ScopeContextHolder.set(new ScopeContext(
                "200",
                Set.of(1L, 2L),              // companies
                Set.of(10L),                  // projects
                Set.of(100L),                 // warehouses
                Set.of(1000L),                // branches
                false));

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        var scopes = r.getAllowedScopes();
        assertEquals(5, scopes.size());
        assertTrue(scopes.stream().anyMatch(s -> "company".equals(s.getScopeType()) && "1".equals(s.getScopeRefId())));
        assertTrue(scopes.stream().anyMatch(s -> "company".equals(s.getScopeType()) && "2".equals(s.getScopeRefId())));
        assertTrue(scopes.stream().anyMatch(s -> "project".equals(s.getScopeType()) && "10".equals(s.getScopeRefId())));
        assertTrue(scopes.stream().anyMatch(s -> "warehouse".equals(s.getScopeType()) && "100".equals(s.getScopeRefId())));
        assertTrue(scopes.stream().anyMatch(s -> "branch".equals(s.getScopeType()) && "1000".equals(s.getScopeRefId())));
    }

    @Test
    void allowedScopes_emptyWhenHolderNull() {
        Jwt jwt = stubLookup("201");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        when(openFga.check(any(), any(), any(), any())).thenReturn(false);
        when(openFga.listObjects(any(), any(), any())).thenReturn(List.of());
        // ScopeContextHolder intentionally left empty.

        AuthzMeResponse r = builder.getAuthzMe(jwt);

        assertTrue(r.getAllowedScopes().isEmpty());
    }

    // ---- Cache behaviour --------------------------------------------------

    @Test
    void sameUserAndVersion_returnsCachedInstance() {
        Jwt jwt = stubLookup("300");
        when(versionProvider.getCurrentVersion()).thenReturn(5L);
        when(openFga.check(eq("300"), eq("admin"), eq("organization"), eq("default"))).thenReturn(true);

        AuthzMeResponse first = builder.getAuthzMe(jwt);
        AuthzMeResponse second = builder.getAuthzMe(jwt);

        assertSame(first, second, "same (user, version) must hit the snapshot cache");
        // Super-admin probe called once only (first build).
        verify(openFga, times(1)).check(eq("300"), eq("admin"), eq("organization"), eq("default"));
    }

    @Test
    void versionBump_invalidatesCache() {
        Jwt jwt = stubLookup("400");
        when(versionProvider.getCurrentVersion()).thenReturn(1L);
        when(openFga.check(eq("400"), eq("admin"), eq("organization"), eq("default"))).thenReturn(true);

        AuthzMeResponse first = builder.getAuthzMe(jwt);

        // Simulate permission-service tuple sync incrementing the authz version.
        when(versionProvider.getCurrentVersion()).thenReturn(2L);
        AuthzMeResponse second = builder.getAuthzMe(jwt);

        // Different cache keys → different instances (fresh build).
        assertFalse(first == second);
        verify(openFga, times(2)).check(eq("400"), eq("admin"), eq("organization"), eq("default"));
    }

    // ---- helpers ----------------------------------------------------------

    private Jwt stubLookup(String responseUserId) {
        Jwt jwt = testJwt("kc-" + responseUserId);
        when(userLookup.resolve(any())).thenReturn(
                new ResolvedAuthenticatedUser(Long.valueOf(responseUserId), responseUserId, null));
        return jwt;
    }

    private static Jwt testJwt(String subject) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("preferred_username", subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build();
    }
}

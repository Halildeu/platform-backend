package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.RequireModule;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthenticatedUserLookupService.ResolvedAuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link RequireModuleInterceptor}.
 *
 * Covers the 2026-04-29 fix:
 *   1. Legacy relation aliases (viewer/manager/admin/editor) map to canonical
 *      OpenFGA module relations (can_view/can_manage/can_edit).
 *   2. Numeric user ID resolution via AuthenticatedUserLookupService matches the
 *      identifier OpenFGA tuples are seeded with (`user:1204` not UUID).
 *   3. Canonical relation names (`can_view` etc.) pass through unchanged.
 */
class RequireModuleInterceptorTest {

    private OpenFgaAuthzService authzService;
    private AuthenticatedUserLookupService lookupService;
    private RequireModuleInterceptor interceptor;

    @BeforeEach
    void setUp() {
        authzService = mock(OpenFgaAuthzService.class);
        lookupService = mock(AuthenticatedUserLookupService.class);
        when(authzService.isEnabled()).thenReturn(true);
        interceptor = new RequireModuleInterceptor(authzService, lookupService);
    }

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    // ---------------------------------------------------------------------
    // Relation alias mapping tests
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("RELATION_ALIASES: viewer → can_view")
    void aliasViewerMapsToCanView() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("viewer")).isEqualTo("can_view");
    }

    @Test
    @DisplayName("RELATION_ALIASES: manager → can_manage")
    void aliasManagerMapsToCanManage() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("manager")).isEqualTo("can_manage");
    }

    @Test
    @DisplayName("RELATION_ALIASES: admin (on module) → can_manage")
    void aliasAdminMapsToCanManage() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("admin")).isEqualTo("can_manage");
    }

    @Test
    @DisplayName("RELATION_ALIASES: editor → can_edit")
    void aliasEditorMapsToCanEdit() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("editor")).isEqualTo("can_edit");
    }

    @Test
    @DisplayName("Canonical can_view passes through unchanged")
    void canonicalCanViewPassesThrough() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("can_view")).isEqualTo("can_view");
    }

    @Test
    @DisplayName("Canonical can_manage passes through unchanged")
    void canonicalCanManagePassesThrough() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("can_manage")).isEqualTo("can_manage");
    }

    @Test
    @DisplayName("Canonical can_edit passes through unchanged")
    void canonicalCanEditPassesThrough() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("can_edit")).isEqualTo("can_edit");
    }

    @Test
    @DisplayName("Unknown relation passes through verbatim (lets OpenFGA surface validation error)")
    void unknownRelationPassesThrough() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation("custom-relation")).isEqualTo("custom-relation");
    }

    @Test
    @DisplayName("Null input returns null safely")
    void nullRelationReturnsNull() {
        assertThat(RequireModuleInterceptor.mapToOpenFgaRelation(null)).isNull();
    }

    // ---------------------------------------------------------------------
    // preHandle integration: alias mapping reaches OpenFGA call
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("preHandle with @RequireModule(relation=\"viewer\") calls authzService.check with \"can_view\"")
    void preHandleMapsViewerAliasInOpenFgaCall() throws Exception {
        Jwt jwt = jwtWithNumericUserId(1204L, "d35-admin@example.com");
        setSecurityContext(jwt);
        when(lookupService.resolve(jwt)).thenReturn(new ResolvedAuthenticatedUser(1204L, "1204", "d35-admin@example.com"));
        when(authzService.check(eq("1204"), eq("can_view"), eq("module"), eq("ACCESS"))).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withViewerAlias");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        verify(authzService).check(eq("1204"), eq("can_view"), eq("module"), eq("ACCESS"));
        verify(authzService, never()).check(eq("1204"), eq("viewer"), any(), any());
    }

    @Test
    @DisplayName("preHandle with @RequireModule(relation=\"can_manage\") passes through unchanged")
    void preHandleCanonicalRelationPassesThrough() throws Exception {
        Jwt jwt = jwtWithNumericUserId(1204L, "d35-admin@example.com");
        setSecurityContext(jwt);
        when(lookupService.resolve(jwt)).thenReturn(new ResolvedAuthenticatedUser(1204L, "1204", "d35-admin@example.com"));
        when(authzService.check(eq("1204"), eq("can_manage"), eq("module"), eq("ACCESS"))).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withCanonicalCanManage");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        verify(authzService).check(eq("1204"), eq("can_manage"), eq("module"), eq("ACCESS"));
    }

    @Test
    @DisplayName("preHandle uses numeric userId from AuthenticatedUserLookupService (not JWT sub UUID)")
    void preHandleUsesNumericUserIdFromLookupService() throws Exception {
        // JWT sub is a UUID (Keycloak); lookup service resolves to numeric ID via email
        Jwt jwt = jwtWithSubject("cbc9a869-1833-4d9c-beea-a9fa52fa851e", "d35-admin@example.com");
        setSecurityContext(jwt);
        when(lookupService.resolve(jwt)).thenReturn(new ResolvedAuthenticatedUser(1204L, "1204", "d35-admin@example.com"));
        when(authzService.check(eq("1204"), eq("can_view"), eq("module"), eq("ACCESS"))).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withCanonicalCanView");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        // Critical: check uses "1204" (numeric — matches tuple `user:1204 admin organization:default`)
        // NOT "cbc9a869-..." (UUID) which was the previous broken behavior.
        verify(authzService).check(eq("1204"), eq("can_view"), eq("module"), eq("ACCESS"));
        verify(authzService, never()).check(eq("cbc9a869-1833-4d9c-beea-a9fa52fa851e"), any(), any(), any());
    }

    @Test
    @DisplayName("preHandle returns 403 when OpenFGA check denies (alias still mapped)")
    void preHandleReturns403OnDeny() throws Exception {
        Jwt jwt = jwtWithNumericUserId(1205L, "d35-granted@example.com");
        setSecurityContext(jwt);
        when(lookupService.resolve(jwt)).thenReturn(new ResolvedAuthenticatedUser(1205L, "1205", "d35-granted@example.com"));
        when(authzService.check(eq("1205"), eq("can_manage"), eq("module"), eq("ACCESS"))).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withManagerAlias");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        verify(authzService, times(1)).check(eq("1205"), eq("can_manage"), eq("module"), eq("ACCESS"));
    }

    @Test
    @DisplayName("preHandle returns 401 when no authenticated user")
    void preHandleReturns401WhenUnauthenticated() throws Exception {
        SecurityContextHolder.clearContext();

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withViewerAlias");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isFalse();
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_UNAUTHORIZED);
        verify(authzService, never()).check(any(), any(), any(), any());
    }

    @Test
    @DisplayName("preHandle skips check when OpenFGA disabled (dev mode)")
    void preHandlePassesThroughInDevMode() throws Exception {
        when(authzService.isEnabled()).thenReturn(false);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withViewerAlias");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        verify(authzService, never()).check(any(), any(), any(), any());
    }

    // ---------------------------------------------------------------------
    // superAdmin bypass (2026-04-29 — organization:default#admin tuple holders
    // bypass module-level guards, mirroring /api/v1/authz/me checkOrganizationAdmin)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("preHandle BYPASSES module check when user is organization:default#admin")
    void preHandleBypassesOnOrganizationAdmin() throws Exception {
        Jwt jwt = jwtWithNumericUserId(1204L, "d35-admin@example.com");
        setSecurityContext(jwt);
        when(lookupService.resolve(jwt)).thenReturn(new ResolvedAuthenticatedUser(1204L, "1204", "d35-admin@example.com"));
        // Organization admin tuple matches → bypass
        when(authzService.check(eq("1204"), eq("admin"), eq("organization"), eq("default"))).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withCanonicalCanManage");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        // organization-level check called (bypass path)
        verify(authzService).check(eq("1204"), eq("admin"), eq("organization"), eq("default"));
        // module-level check NOT called (bypass)
        verify(authzService, never()).check(eq("1204"), eq("can_manage"), eq("module"), eq("ACCESS"));
    }

    @Test
    @DisplayName("preHandle FALLS THROUGH to module check when user is NOT organization admin")
    void preHandleFallsThroughWhenNotOrgAdmin() throws Exception {
        Jwt jwt = jwtWithNumericUserId(1205L, "d35-granted@example.com");
        setSecurityContext(jwt);
        when(lookupService.resolve(jwt)).thenReturn(new ResolvedAuthenticatedUser(1205L, "1205", "d35-granted@example.com"));
        // Not org admin
        when(authzService.check(eq("1205"), eq("admin"), eq("organization"), eq("default"))).thenReturn(false);
        // Module-level grants can_view
        when(authzService.check(eq("1205"), eq("can_view"), eq("module"), eq("ACCESS"))).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withCanonicalCanView");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        verify(authzService).check(eq("1205"), eq("admin"), eq("organization"), eq("default"));
        verify(authzService).check(eq("1205"), eq("can_view"), eq("module"), eq("ACCESS"));
    }

    @Test
    @DisplayName("preHandle SAFE FALL-THROUGH when organization admin check throws (no panic, module check evaluated)")
    void preHandleFallsThroughWhenOrgAdminCheckFails() throws Exception {
        Jwt jwt = jwtWithNumericUserId(1204L, "d35-admin@example.com");
        setSecurityContext(jwt);
        when(lookupService.resolve(jwt)).thenReturn(new ResolvedAuthenticatedUser(1204L, "1204", "d35-admin@example.com"));
        // Organization-level OpenFGA call throws (network glitch, OpenFGA hiccup)
        when(authzService.check(eq("1204"), eq("admin"), eq("organization"), eq("default")))
                .thenThrow(new RuntimeException("simulated openfga outage"));
        // Module-level still works → grant via direct module tuple
        when(authzService.check(eq("1204"), eq("can_manage"), eq("module"), eq("ACCESS"))).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        HandlerMethod handler = handlerFor("withCanonicalCanManage");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).isTrue();
        verify(authzService).check(eq("1204"), eq("can_manage"), eq("module"), eq("ACCESS"));
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static Jwt jwtWithNumericUserId(long userId, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("userId", userId);
        claims.put("email", email);
        claims.put("sub", "subject-" + userId);
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static Jwt jwtWithSubject(String subject, String email) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", subject);
        claims.put("email", email);
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .claims(c -> c.putAll(claims))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
    }

    private static void setSecurityContext(Jwt jwt) {
        TestingAuthenticationToken auth = new TestingAuthenticationToken(jwt, null, "ROLE_USER");
        auth.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    private static HandlerMethod handlerFor(String methodName) throws NoSuchMethodException {
        Method method = TestController.class.getMethod(methodName);
        return new HandlerMethod(new TestController(), method);
    }

    /** Test controller carrying the @RequireModule annotation in legacy + canonical forms. */
    static class TestController {
        @RequireModule(value = "ACCESS", relation = "viewer")
        public void withViewerAlias() {}

        @RequireModule(value = "ACCESS", relation = "manager")
        public void withManagerAlias() {}

        @RequireModule(value = "ACCESS", relation = "can_view")
        public void withCanonicalCanView() {}

        @RequireModule(value = "ACCESS", relation = "can_manage")
        public void withCanonicalCanManage() {}
    }
}

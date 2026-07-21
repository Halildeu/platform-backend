package com.example.permission.controller;

import com.example.commonauth.identity.AuthenticatedPrincipalResolver;
import com.example.commonauth.identity.ResolvedUserIdentity;
import com.example.permission.dto.v1.AuthzMeResponseDto;
import com.example.permission.repository.RolePermissionRepository;
import com.example.permission.repository.UserRoleAssignmentRepository;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthorizationQueryService;
import com.example.permission.service.PermissionService;
import com.example.permission.service.PermissionCatalogService;
import com.example.permission.service.TupleSyncService;
import com.example.permission.dto.PermissionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationControllerV1Test {

    @Mock
    private AuthorizationQueryService authorizationQueryService;

    @Mock
    private AuthenticatedUserLookupService authenticatedUserLookupService;

    @Mock
    private AuthenticatedPrincipalResolver principalResolver;

    @Mock
    private PermissionService permissionService;

    @Mock
    private PermissionCatalogService catalogService;

    @Mock
    private TupleSyncService tupleSyncService;

    @Mock
    private com.example.commonauth.openfga.OpenFgaAuthzService authzService;

    @Mock
    private UserRoleAssignmentRepository assignmentRepository;

    @Mock
    private RolePermissionRepository rolePermissionRepository;

    @Mock
    private com.example.permission.service.AuthzVersionService authzVersionService;

    @InjectMocks
    private AuthorizationControllerV1 controller;

    // Helper: canonical resolved principal for the given numeric id and subject/email.
    // subjectMatched=false because the adapter this test suite exercises resolves via the email
    // fallback path (kc_subject not consulted yet). enabled=true/deleted=false keep behaviour
    // aligned with the pre-wire lookup semantics; the follow-up PR that migrates the transport
    // to the canonical /resolve endpoint tightens these gates.
    private static AuthenticatedPrincipalResolver.Resolution resolved(
            long userId, String subject, String email) {
        return new AuthenticatedPrincipalResolver.Resolution(
                AuthenticatedPrincipalResolver.Outcome.RESOLVED,
                new ResolvedUserIdentity(userId, subject, email, false, true, false, null));
    }

    private static AuthenticatedPrincipalResolver.Resolution failed(
            AuthenticatedPrincipalResolver.Outcome outcome) {
        return new AuthenticatedPrincipalResolver.Resolution(outcome, null);
    }

    @Test
    void getMe_usesResolvedNumericUserIdForScopeSummary() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4")
                .claim("permissions", List.of("MANAGE_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(principalResolver.resolve(jwt))
                .thenReturn(resolved(15L, jwt.getSubject(), "admin@example.com"));
        // board #2531: /authz/me artık numeric id'nin YANINDA doğrulanmış KC sub'ı da alias
        // olarak geçiriyor — canonical /access/scope grant'leri user:<sub> altında saklanıyor.
        when(authorizationQueryService.getUserScopeSummary(
                15L, "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4"))
                .thenReturn(Map.of("COMPANY", Set.of(11L)));
        when(catalogService.getModuleKeys()).thenReturn(List.of());
        PermissionResponse assignment = new PermissionResponse();
        assignment.setPermissions(Set.of("VIEW_USERS", "MANAGE_USERS"));
        when(permissionService.getAssignments(15L, null, null, null))
                .thenReturn(List.of(assignment));
        when(assignmentRepository.findActiveAssignments(15L)).thenReturn(List.of());

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("15", body.getUserId());
        // Faz 23.5 hardening — Codex thread 019e0316 iter-3 absorb:
        // additive subscriberId mirrors the numeric userId.
        assertEquals(15L, body.getSubscriberId());
        assertEquals(Set.of("VIEW_USERS", "MANAGE_USERS"), body.getPermissions());
        assertEquals(1, body.getAllowedScopes().size());
        assertEquals("COMPANY", body.getAllowedScopes().get(0).scopeType());
        assertEquals(11L, body.getAllowedScopes().get(0).scopeRefId());
    }

    // board #2532 wire step: the pre-wire behaviour rebuilt JWT permissions onto a
    // "PROFILE_MISSING" principal, so an unresolved user still got their JWT permissions
    // echoed back. That is precisely the "claim as authority" pattern umbrella #2530 removes:
    // if user-service does not know this principal, /authz/me carries an empty, non-privileged
    // snapshot rather than trusting the token to describe itself. FE contract (200 body shape)
    // is preserved — only the permissions/modules/scopes surfaces are empty.
    @Test
    void getMe_profileMissing_failsClosedWithEmptySnapshot() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4")
                .claim("permissions", List.of("VIEW_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(principalResolver.resolve(jwt))
                .thenReturn(failed(AuthenticatedPrincipalResolver.Outcome.PROFILE_MISSING));

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertNull(body.getSubscriberId(),
                "PROFILE_MISSING must NOT project a subscriberId — the alias exists to catch drift");
        assertTrue(body.getPermissions().isEmpty(),
                "PROFILE_MISSING must NOT echo JWT permissions — that is the claim-as-authority bug");
        assertTrue(body.getAllowedModules().isEmpty());
        assertFalse(body.isSuperAdmin());
    }

    @Test
    void getMe_identityMismatch_failsClosedIgnoringJwtPermissions() {
        // Slice 2b (#727, Codex 019ef3ca REVISE) generalised: a userId/uid claim that
        // contradicts the canonical row must NOT have authz rebuilt from JWT permissions/roles —
        // even an "admin" permissions claim yields an empty, non-privileged snapshot.
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("kc-uuid-not-numeric")
                .claim("uid", 42L)
                .claim("email", "ghost@example.com")
                .claim("permissions", List.of("admin", "MANAGE_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(principalResolver.resolve(jwt))
                .thenReturn(failed(AuthenticatedPrincipalResolver.Outcome.IDENTITY_MISMATCH));

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertNull(body.getSubscriberId());
        assertTrue(body.getPermissions().isEmpty());
        assertTrue(body.getAllowedModules().isEmpty());
        assertFalse(body.isSuperAdmin());
    }

    @Test
    void getMe_directoryUnavailable_returns503() {
        // The point of the DIRECTORY_UNAVAILABLE outcome is that an outage in user-service
        // must NOT silently degrade to a deny/allow decision built from unverified claims.
        // /authz/me surfaces this as 503 AUTHZ_DEGRADED (contract: 5xx ⇒ non-empty JSON error).
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("15")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(principalResolver.resolve(jwt))
                .thenReturn(failed(AuthenticatedPrincipalResolver.Outcome.DIRECTORY_UNAVAILABLE));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.getMe(jwt));
        assertEquals(503, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("AUTHZ_DEGRADED"),
                "503 reason must carry AUTHZ_DEGRADED so the FE can classify the failure");
    }

    @Test
    void getMe_accountDisabled_returns403() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("15")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(principalResolver.resolve(jwt))
                .thenReturn(failed(AuthenticatedPrincipalResolver.Outcome.ACCOUNT_DISABLED));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.getMe(jwt));
        assertEquals(403, ex.getStatusCode().value());
        assertTrue(ex.getReason() != null && ex.getReason().contains("ACCOUNT_DISABLED"));
    }

    @Test
    void getMe_userDeleted_returns404() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("15")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(principalResolver.resolve(jwt))
                .thenReturn(failed(AuthenticatedPrincipalResolver.Outcome.USER_DELETED));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.getMe(jwt));
        assertEquals(404, ex.getStatusCode().value());
        assertTrue(ex.getReason() != null && ex.getReason().contains("USER_DELETED"));
    }

    @Test
    void getMe_buildsFrontendCompatibleModuleFallbackWhenEnhancedFieldsFail() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("15")
                .claim("permissions", List.of("ACCESS", "AUDIT", "REPORT"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(principalResolver.resolve(jwt))
                .thenReturn(resolved(15L, jwt.getSubject(), "user3@example.com"));
        when(authorizationQueryService.getUserScopeSummary(eq(15L), any())).thenReturn(Map.of());
        when(catalogService.getModuleKeys()).thenReturn(List.of("ACCESS", "AUDIT", "REPORT", "THEME"));
        when(authzService.check("15", "can_manage", "module", "ACCESS")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "ACCESS")).thenReturn(true);
        when(authzService.check("15", "can_manage", "module", "AUDIT")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "AUDIT")).thenReturn(true);
        when(authzService.check("15", "can_manage", "module", "REPORT")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "REPORT")).thenReturn(true);
        when(authzService.check("15", "can_manage", "module", "THEME")).thenReturn(false);
        when(authzService.check("15", "can_view", "module", "THEME")).thenReturn(false);
        when(assignmentRepository.findActiveAssignments(15L))
                .thenThrow(new RuntimeException("role repository unavailable"));

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertNotNull(body.getModules());
        assertEquals("VIEW", body.getModules().get("ACCESS"));
        assertEquals("VIEW", body.getModules().get("AUDIT"));
        assertEquals("VIEW", body.getModules().get("REPORT"));
        assertTrue(body.getAllowedModules().contains("ACCESS"));
        assertTrue(body.getAllowedModules().contains("AUDIT"));
        assertTrue(body.getAllowedModules().contains("REPORT"));
    }

    @Test
    void getMe_projectsInterviewEvidenceViewFromExplicitNamedRoleWithoutSuperAdmin() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2501")
                .claim("permissions", List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(principalResolver.resolve(jwt))
                .thenReturn(resolved(2501L, jwt.getSubject(), "p5-readiness-viewer@localtest.me"));
        when(authorizationQueryService.getUserScopeSummary(eq(2501L), any())).thenReturn(Map.of());
        when(catalogService.getModuleKeys()).thenReturn(List.of("INTERVIEW_EVIDENCE"));
        when(authzService.check("2501", "admin", "organization", "default")).thenReturn(false);
        when(authzService.check("2501", "can_manage", "module", "INTERVIEW_EVIDENCE"))
                .thenReturn(false);
        when(authzService.check("2501", "can_view", "module", "INTERVIEW_EVIDENCE"))
                .thenReturn(true);
        when(permissionService.getAssignments(2501L, null, null, null)).thenReturn(List.of());

        var role = new com.example.permission.model.Role();
        role.setId(832L);
        role.setName("P5_READINESS_VIEWER");
        var assignment = new com.example.permission.model.UserRoleAssignment();
        assignment.setRole(role);
        var granule = new com.example.permission.model.RolePermission(
                role,
                com.example.permission.model.PermissionType.MODULE,
                "INTERVIEW_EVIDENCE",
                com.example.permission.model.GrantType.VIEW);

        when(assignmentRepository.findActiveAssignments(2501L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(List.of(832L))).thenReturn(List.of(granule));
        when(tupleSyncService.resolveEffectiveGrants(List.of(granule)))
                .thenReturn(Map.of(
                        "MODULE:INTERVIEW_EVIDENCE",
                        new TupleSyncService.ResolvedGrant(
                                com.example.permission.model.GrantType.VIEW,
                                "P5_READINESS_VIEWER")));

        AuthzMeResponseDto body = controller.getMe(jwt).getBody();

        assertNotNull(body);
        assertFalse(body.isSuperAdmin());
        assertEquals(List.of("P5_READINESS_VIEWER"), body.getRoles());
        assertEquals(Map.of("INTERVIEW_EVIDENCE", "VIEW"), body.getModules());
        assertEquals(List.of("INTERVIEW_EVIDENCE"), body.getAllowedModules());
        assertFalse(body.getPermissions().stream().anyMatch("admin"::equalsIgnoreCase));
    }

    @Test
    void getMe_doesNotProjectInterviewEvidenceFromAdminRoleWithoutExplicitGranule() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2502")
                .claim("permissions", List.of())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();

        when(principalResolver.resolve(jwt))
                .thenReturn(resolved(2502L, jwt.getSubject(), "catalog-admin-without-p5-grant@localtest.me"));
        when(authorizationQueryService.getUserScopeSummary(eq(2502L), any())).thenReturn(Map.of());
        when(catalogService.getModuleKeys())
                .thenReturn(List.of("USER_MANAGEMENT", "INTERVIEW_EVIDENCE"));
        when(authzService.check("2502", "admin", "organization", "default")).thenReturn(false);
        when(authzService.check("2502", "can_manage", "module", "USER_MANAGEMENT"))
                .thenReturn(false);
        when(authzService.check("2502", "can_view", "module", "USER_MANAGEMENT"))
                .thenReturn(false);
        when(authzService.check("2502", "can_manage", "module", "INTERVIEW_EVIDENCE"))
                .thenReturn(false);
        when(authzService.check("2502", "can_view", "module", "INTERVIEW_EVIDENCE"))
                .thenReturn(false);
        when(permissionService.getAssignments(2502L, null, null, null)).thenReturn(List.of());

        var adminRole = new com.example.permission.model.Role();
        adminRole.setId(833L);
        adminRole.setName("ADMIN");
        var assignment = new com.example.permission.model.UserRoleAssignment();
        assignment.setRole(adminRole);

        when(assignmentRepository.findActiveAssignments(2502L)).thenReturn(List.of(assignment));
        when(rolePermissionRepository.findByRoleIdIn(List.of(833L))).thenReturn(List.of());
        when(tupleSyncService.resolveEffectiveGrants(List.of())).thenReturn(Map.of());

        AuthzMeResponseDto body = controller.getMe(jwt).getBody();

        assertNotNull(body);
        assertFalse(body.isSuperAdmin());
        assertEquals(List.of("ADMIN"), body.getRoles());
        assertEquals(Map.of("USER_MANAGEMENT", "MANAGE"), body.getModules());
        assertEquals(List.of("USER_MANAGEMENT"), body.getAllowedModules());
        assertFalse(body.getModules().containsKey("INTERVIEW_EVIDENCE"));
        assertFalse(body.getPermissions().stream().anyMatch("admin"::equalsIgnoreCase));
    }

    // Codex 019dddb7 iter-42 — /authz/me 5xx contract.
    // Pre-iter-42 the controller returned ResponseEntity.status(503).body(null)
    // on any RuntimeException, which the api-gateway / variant-service chain
    // collapsed to "200 + empty body" on the wire (see live-capture
    // diagnostic in iter-34). The frontend's iter-34 retry was a workaround
    // for that contract violation.
    //
    // Post-iter-42 the broad catch rethrows as ResponseStatusException so
    // the GlobalExceptionHandler emits a typed 503 with a non-empty JSON
    // body. The contract is asserted at the unit layer here AND at the
    // gateway layer in the new gateway integration test.
    @Test
    void getMe_returns503ResponseStatusExceptionWhenDownstreamFails() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("15")
                .claim("permissions", List.of("VIEW_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(principalResolver.resolve(jwt))
                .thenReturn(resolved(15L, jwt.getSubject(), "u@example.com"));
        // doGetMe wraps most downstream calls in *Safely helpers; the
        // remaining unguarded path is authzVersionService.getCurrentVersion
        // which fires unconditionally and surfaces RuntimeException to the
        // broad catch.
        when(authzVersionService.getCurrentVersion())
                .thenThrow(new RuntimeException("downstream synthetic failure"));

        org.springframework.web.server.ResponseStatusException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.web.server.ResponseStatusException.class,
                        () -> controller.getMe(jwt)
                );
        assertEquals(503, ex.getStatusCode().value());
        assertNotNull(ex.getReason());
        assertTrue(ex.getReason().contains("AUTHZ_DEGRADED"),
                "503 reason must carry AUTHZ_DEGRADED so frontend can classify the error");
    }

// ---- B1 (Rev 19): Tests for new /check, /batch-check endpoints ----

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("B1: /authz/check endpoint")
    class CheckEndpoint {

        @Test
        @org.junit.jupiter.api.DisplayName("check returns 200 with allowed=true when OpenFGA allows")
        void check_returnsAllowed() {
            var request = new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "HR_REPORTS");
            when(authzService.checkWithReason("0", "can_view", "report", "HR_REPORTS"))
                    .thenReturn(new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(true, "ALLOWED"));

            // CNS-004 fix #2: check() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<Map<String, Object>> response = controller.check(request, null);

            assertEquals(200, response.getStatusCode().value());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertEquals(true, body.get("allowed"));
            assertEquals("ALLOWED", body.get("reason"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("check returns 200 with allowed=false when OpenFGA denies (no 403)")
        void check_returnsDenied() {
            var request = new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "SECRET_REPORT");
            when(authzService.checkWithReason("0", "can_view", "report", "SECRET_REPORT"))
                    .thenReturn(new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(false, "blocked"));

            // CNS-004 fix #2: check() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<Map<String, Object>> response = controller.check(request, null);

            // B3 semantics: deny is in payload, NOT in HTTP status
            assertEquals(200, response.getStatusCode().value());
            Map<String, Object> body = response.getBody();
            assertNotNull(body);
            assertEquals(false, body.get("allowed"));
            assertEquals("blocked", body.get("reason"));
        }
    }

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("B1: /authz/batch-check endpoint")
    class BatchCheckEndpoint {

        @Test
        @org.junit.jupiter.api.DisplayName("batch-check returns 400 when checks array is empty")
        void batchCheck_emptyReturns400() {
            var request = new AuthorizationControllerV1.BatchCheckRequest(List.of());

            // CNS-004 fix #2: batchCheck() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<?> response = controller.batchCheck(request, null);

            assertEquals(400, response.getStatusCode().value());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("batch-check returns 400 when >20 checks")
        void batchCheck_tooManyReturns400() {
            var checks = new java.util.ArrayList<AuthorizationControllerV1.AuthzCheckRequest>();
            for (int i = 0; i < 21; i++) {
                checks.add(new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "R" + i));
            }
            var request = new AuthorizationControllerV1.BatchCheckRequest(checks);

            // CNS-004 fix #2: batchCheck() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<?> response = controller.batchCheck(request, null);

            assertEquals(400, response.getStatusCode().value());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("batch-check returns 200 with mixed results")
        void batchCheck_mixedResults() {
            var checks = List.of(
                    new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "R1"),
                    new AuthorizationControllerV1.AuthzCheckRequest("can_view", "report", "R2")
            );
            var request = new AuthorizationControllerV1.BatchCheckRequest(checks);

            when(authzService.batchCheck(org.mockito.ArgumentMatchers.eq("0"), org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(List.of(
                            new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(true, "ALLOWED"),
                            new com.example.commonauth.openfga.OpenFgaAuthzService.CheckResult(false, "blocked")
                    ));

            // CNS-004 fix #2: batchCheck() signature genisletildi (Jwt param). Mock null → "0" fallback.
            ResponseEntity<?> response = controller.batchCheck(request, null);

            assertEquals(200, response.getStatusCode().value());
        }
    }

    // ---- P1.9: NO_SCOPE explain path ----

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("P1.9: /authz/explain NO_SCOPE path")
    class ExplainNoScope {

        @Test
        @org.junit.jupiter.api.DisplayName("explain with scopeType+scopeRefId outside userScopes returns NO_SCOPE with both permission and scope fields populated")
        void explain_noScope_preservesPermissionAndScopeSlots() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of("COMPANY", Set.of(11L)));
            when(assignmentRepository.findActiveAssignments(15L))
                    .thenReturn(List.of());

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "COMPANY");
            request.put("scopeRefId", "99");

            ResponseEntity<com.example.permission.dto.v1.ExplainResponseDto> response = controller.explain(request);

            assertEquals(200, response.getStatusCode().value());
            com.example.permission.dto.v1.ExplainResponseDto body = response.getBody();
            assertNotNull(body);
            org.junit.jupiter.api.Assertions.assertFalse(body.allowed());
            assertEquals("NO_SCOPE", body.reason());
            assertEquals("MODULE", body.details().permissionType());
            assertEquals("PURCHASE", body.details().permissionKey());
            assertEquals("COMPANY", body.details().scopeType());
            assertEquals(99L, body.details().scopeRefId());
            org.junit.jupiter.api.Assertions.assertNull(body.details().roleName());
            org.junit.jupiter.api.Assertions.assertNull(body.details().grantType());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("explain with scopeType in userScopes falls through to permission evaluation (no NO_SCOPE short-circuit)")
        void explain_scopeInUserScopes_doesNotShortCircuit() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of("COMPANY", Set.of(11L, 35L)));
            when(assignmentRepository.findActiveAssignments(15L))
                    .thenReturn(List.of());

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "COMPANY");
            request.put("scopeRefId", "35");

            ResponseEntity<com.example.permission.dto.v1.ExplainResponseDto> response = controller.explain(request);

            assertEquals(200, response.getStatusCode().value());
            com.example.permission.dto.v1.ExplainResponseDto body = response.getBody();
            assertNotNull(body);
            // NO_ROLE fallback — user has no role assignments, so permission evaluation yields NO_ROLE, not NO_SCOPE
            assertEquals("NO_ROLE", body.reason());
            assertEquals("MODULE", body.details().permissionType());
            assertEquals("PURCHASE", body.details().permissionKey());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("explain with blank scopeType is treated as absent — skips scope check")
        void explain_blankScopeType_skipsScopeCheck() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of());
            when(assignmentRepository.findActiveAssignments(15L))
                    .thenReturn(List.of());

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "");  // blank — ignored
            request.put("scopeRefId", "99");

            ResponseEntity<com.example.permission.dto.v1.ExplainResponseDto> response = controller.explain(request);

            assertEquals(200, response.getStatusCode().value());
            com.example.permission.dto.v1.ExplainResponseDto body = response.getBody();
            assertNotNull(body);
            // Skips NO_SCOPE, falls through to NO_ROLE
            assertEquals("NO_ROLE", body.reason());
        }

        @Test
        @org.junit.jupiter.api.DisplayName("explain with non-numeric scopeRefId returns 400")
        void explain_nonNumericScopeRefId_returns400() {
            when(authorizationQueryService.getUserScopeSummary(15L))
                    .thenReturn(Map.of("COMPANY", Set.of(11L)));

            Map<String, String> request = new java.util.HashMap<>();
            request.put("userId", "15");
            request.put("permissionType", "MODULE");
            request.put("permissionKey", "PURCHASE");
            request.put("scopeType", "COMPANY");
            request.put("scopeRefId", "not-a-number");

            org.springframework.web.server.ResponseStatusException ex =
                    org.junit.jupiter.api.Assertions.assertThrows(
                            org.springframework.web.server.ResponseStatusException.class,
                            () -> controller.explain(request));
            assertEquals(400, ex.getStatusCode().value());
            assertTrue(ex.getReason() != null && ex.getReason().contains("scopeRefId"));
        }
    }

    // ---- 2026-05-17 (Codex thread 019e34df): /authz/me REPORT projection invariant ----
    //
    // Regression guard for the multi-layer authz inconsistency found via
    // browser impersonation of the `d35-granted` persona: REPORT_VIEWER seeds
    // only PermissionType.REPORT granules, so `/authz/me.reports` was full of
    // ALLOW entries while `modules` had no REPORT key (FE hid the nav) and the
    // legacy `permissions` set had no REPORT_VIEW (report-service denied all
    // reports → /api/v1/reports = 0). The three surfaces must now agree.

    @org.junit.jupiter.api.Nested
    @org.junit.jupiter.api.DisplayName("Codex 019e34df: /authz/me REPORT module/permission invariant")
    class ReportModuleInvariant {

        private Jwt jwt(String subject) {
            return Jwt.withTokenValue("token")
                    .header("alg", "none")
                    .subject(subject)
                    .claim("permissions", List.of())
                    .issuedAt(Instant.now())
                    .expiresAt(Instant.now().plusSeconds(300))
                    .build();
        }

        private com.example.permission.model.UserRoleAssignment assignment(long roleId, String roleName) {
            var role = new com.example.permission.model.Role();
            role.setId(roleId);
            role.setName(roleName);
            var a = new com.example.permission.model.UserRoleAssignment();
            a.setRole(role);
            return a;
        }

        @Test
        @org.junit.jupiter.api.DisplayName("positive REPORT grant surfaces modules.REPORT=VIEW + permissions REPORT_VIEW even when no MODULE:REPORT granule exists")
        void reportGrant_surfacesModuleAndPermission() {
            Jwt token = jwt("1205");
            when(principalResolver.resolve(token))
                    .thenReturn(resolved(1205L, token.getSubject(), "d35-granted@example.com"));
            when(authorizationQueryService.getUserScopeSummary(eq(1205L), any())).thenReturn(Map.of());
            // resolvePermissions: USER_MANAGEMENT module tuple resolved; the DB
            // legacy permissions deliberately omit REPORT_VIEW so the assertion
            // proves the projection invariant *injected* it.
            when(catalogService.getModuleKeys()).thenReturn(List.of("USER_MANAGEMENT", "REPORT"));
            when(authzService.check("1205", "can_manage", "module", "USER_MANAGEMENT")).thenReturn(false);
            when(authzService.check("1205", "can_view", "module", "USER_MANAGEMENT")).thenReturn(true);
            when(authzService.check("1205", "can_manage", "module", "REPORT")).thenReturn(false);
            when(authzService.check("1205", "can_view", "module", "REPORT")).thenReturn(false);
            PermissionResponse dbAssignment = new PermissionResponse();
            dbAssignment.setPermissions(Set.of("VIEW_USERS"));
            when(permissionService.getAssignments(1205L, null, null, null))
                    .thenReturn(List.of(dbAssignment));
            // granule path: REPORT_VIEWER role → REPORT-type grant only, no MODULE:REPORT
            when(assignmentRepository.findActiveAssignments(1205L))
                    .thenReturn(List.of(assignment(50L, "REPORT_VIEWER")));
            when(rolePermissionRepository.findByRoleIdIn(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(List.of());
            when(tupleSyncService.resolveEffectiveGrants(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(Map.of("REPORT:FINANCE_REPORTS",
                            new TupleSyncService.ResolvedGrant(
                                    com.example.permission.model.GrantType.VIEW, "REPORT_VIEWER")));

            AuthzMeResponseDto body = controller.getMe(token).getBody();

            assertNotNull(body);
            assertEquals("ALLOW", body.getReports().get("FINANCE_REPORTS"));
            assertEquals("VIEW", body.getModules().get("REPORT"),
                    "positive REPORT grant must surface modules.REPORT");
            assertTrue(body.getPermissions().contains("REPORT_VIEW"),
                    "positive REPORT grant must surface REPORT_VIEW so report-service grants reports");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("MANAGE REPORT grant surfaces modules.REPORT=MANAGE + REPORT_EXPORT/REPORT_MANAGE")
        void reportManageGrant_surfacesManage() {
            Jwt token = jwt("1206");
            when(principalResolver.resolve(token))
                    .thenReturn(resolved(1206L, token.getSubject(), "report-mgr@example.com"));
            when(authorizationQueryService.getUserScopeSummary(eq(1206L), any())).thenReturn(Map.of());
            when(catalogService.getModuleKeys()).thenReturn(List.of());
            when(permissionService.getAssignments(1206L, null, null, null)).thenReturn(List.of());
            when(assignmentRepository.findActiveAssignments(1206L))
                    .thenReturn(List.of(assignment(51L, "REPORT_MANAGER")));
            when(rolePermissionRepository.findByRoleIdIn(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(List.of());
            when(tupleSyncService.resolveEffectiveGrants(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(Map.of("REPORT:HR_REPORTS",
                            new TupleSyncService.ResolvedGrant(
                                    com.example.permission.model.GrantType.MANAGE, "REPORT_MANAGER")));

            AuthzMeResponseDto body = controller.getMe(token).getBody();

            assertNotNull(body);
            assertEquals("MANAGE", body.getModules().get("REPORT"));
            assertTrue(body.getPermissions().contains("REPORT_VIEW"));
            assertTrue(body.getPermissions().contains("REPORT_EXPORT"));
            assertTrue(body.getPermissions().contains("REPORT_MANAGE"));
        }

        @Test
        @org.junit.jupiter.api.DisplayName("explicit MODULE:REPORT DENY granule wins — module stays DENY, REPORT_VIEW not injected")
        void reportModuleDeny_winsOverReportGrant() {
            Jwt token = jwt("1207");
            when(principalResolver.resolve(token))
                    .thenReturn(resolved(1207L, token.getSubject(), "report-denied@example.com"));
            when(authorizationQueryService.getUserScopeSummary(eq(1207L), any())).thenReturn(Map.of());
            when(catalogService.getModuleKeys()).thenReturn(List.of());
            PermissionResponse dbAssignment = new PermissionResponse();
            dbAssignment.setPermissions(Set.of("VIEW_USERS"));
            when(permissionService.getAssignments(1207L, null, null, null))
                    .thenReturn(List.of(dbAssignment));
            when(assignmentRepository.findActiveAssignments(1207L))
                    .thenReturn(List.of(assignment(52L, "REPORT_VIEWER")));
            when(rolePermissionRepository.findByRoleIdIn(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(List.of());
            java.util.Map<String, TupleSyncService.ResolvedGrant> effective = new java.util.LinkedHashMap<>();
            effective.put("REPORT:FINANCE_REPORTS", new TupleSyncService.ResolvedGrant(
                    com.example.permission.model.GrantType.VIEW, "REPORT_VIEWER"));
            effective.put("MODULE:REPORT", new TupleSyncService.ResolvedGrant(
                    com.example.permission.model.GrantType.DENY, "RESTRICTED"));
            when(tupleSyncService.resolveEffectiveGrants(org.mockito.ArgumentMatchers.anyList()))
                    .thenReturn(effective);

            AuthzMeResponseDto body = controller.getMe(token).getBody();

            assertNotNull(body);
            assertEquals("DENY", body.getModules().get("REPORT"),
                    "explicit MODULE:REPORT DENY must not be overwritten by the invariant");
            org.junit.jupiter.api.Assertions.assertFalse(body.getPermissions().contains("REPORT_VIEW"),
                    "REPORT_VIEW must not be injected when the REPORT module is explicitly denied");
        }

        @Test
        @org.junit.jupiter.api.DisplayName("resolvePermissions merges OpenFGA module grants with legacy DB permissions instead of short-circuiting")
        void resolvePermissions_mergesOpenFgaAndDbPermissions() {
            Jwt token = jwt("1208");
            when(principalResolver.resolve(token))
                    .thenReturn(resolved(1208L, token.getSubject(), "merge@example.com"));
            when(authorizationQueryService.getUserScopeSummary(eq(1208L), any())).thenReturn(Map.of());
            when(catalogService.getModuleKeys()).thenReturn(List.of("USER_MANAGEMENT"));
            when(authzService.check("1208", "can_manage", "module", "USER_MANAGEMENT")).thenReturn(false);
            when(authzService.check("1208", "can_view", "module", "USER_MANAGEMENT")).thenReturn(true);
            PermissionResponse dbAssignment = new PermissionResponse();
            dbAssignment.setPermissions(Set.of("REPORT_VIEW", "scope.all-companies-fin"));
            when(permissionService.getAssignments(1208L, null, null, null))
                    .thenReturn(List.of(dbAssignment));
            when(assignmentRepository.findActiveAssignments(1208L)).thenReturn(List.of());

            AuthzMeResponseDto body = controller.getMe(token).getBody();

            assertNotNull(body);
            // Pre-fix: a non-empty OpenFGA result short-circuited and returned
            // only {USER_MANAGEMENT}, dropping REPORT_VIEW + scope.* — so
            // report-service denied every report.
            assertTrue(body.getPermissions().contains("USER_MANAGEMENT"));
            assertTrue(body.getPermissions().contains("REPORT_VIEW"));
            assertTrue(body.getPermissions().contains("scope.all-companies-fin"));
        }
    }
}

package com.example.permission.controller;

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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationControllerV1Test {

    @Mock
    private AuthorizationQueryService authorizationQueryService;

    @Mock
    private AuthenticatedUserLookupService authenticatedUserLookupService;

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

    @Test
    void getMe_usesResolvedNumericUserIdForScopeSummary() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4")
                .claim("permissions", List.of("MANAGE_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(authenticatedUserLookupService.resolve(jwt))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(15L, "15", "admin@example.com"));
        when(authorizationQueryService.getUserScopeSummary(15L))
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
        assertEquals(Set.of("VIEW_USERS", "MANAGE_USERS"), body.getPermissions());
        assertEquals(1, body.getAllowedScopes().size());
        assertEquals("COMPANY", body.getAllowedScopes().get(0).scopeType());
        assertEquals(11L, body.getAllowedScopes().get(0).scopeRefId());
    }

    @Test
    void getMe_returnsPermissionsOnlyWhenNumericUserCannotBeResolved() {
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "none")
                .subject("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4")
                .claim("permissions", List.of("VIEW_USERS"))
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300))
                .build();
        when(authenticatedUserLookupService.resolve(jwt))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(null, "2fd0e4f7-c9da-4622-b4b6-b90adab28dd4", null));

        ResponseEntity<AuthzMeResponseDto> response = controller.getMe(jwt);

        assertEquals(200, response.getStatusCode().value());
        AuthzMeResponseDto body = response.getBody();
        assertNotNull(body);
        assertEquals("2fd0e4f7-c9da-4622-b4b6-b90adab28dd4", body.getUserId());
        assertEquals(Set.of("VIEW_USERS"), body.getPermissions());
        assertEquals(List.of(), body.getAllowedScopes());
        assertEquals(List.of(), body.getScopes());
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

        when(authenticatedUserLookupService.resolve(jwt))
                .thenReturn(new AuthenticatedUserLookupService.ResolvedAuthenticatedUser(15L, "15", "user3@example.com"));
        when(authorizationQueryService.getUserScopeSummary(15L)).thenReturn(Map.of());
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

            ResponseEntity<Map<String, Object>> response = controller.check(request);

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

            ResponseEntity<Map<String, Object>> response = controller.check(request);

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

            ResponseEntity<?> response = controller.batchCheck(request);

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

            ResponseEntity<?> response = controller.batchCheck(request);

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

            ResponseEntity<?> response = controller.batchCheck(request);

            assertEquals(200, response.getStatusCode().value());
        }
    }
}

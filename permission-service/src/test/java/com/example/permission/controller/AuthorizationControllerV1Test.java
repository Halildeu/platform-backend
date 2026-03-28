package com.example.permission.controller;

import com.example.permission.dto.v1.AuthzMeResponseDto;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthorizationQueryService;
import com.example.permission.service.PermissionService;
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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthorizationControllerV1Test {

    @Mock
    private AuthorizationQueryService authorizationQueryService;

    @Mock
    private AuthenticatedUserLookupService authenticatedUserLookupService;

    @Mock
    private PermissionService permissionService;

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
        PermissionResponse assignment = new PermissionResponse();
        assignment.setPermissions(Set.of("VIEW_USERS", "MANAGE_USERS"));
        when(permissionService.getAssignments(15L, null, null, null))
                .thenReturn(List.of(assignment));

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
}

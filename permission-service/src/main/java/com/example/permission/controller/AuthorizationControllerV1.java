package com.example.permission.controller;

import com.example.permission.dto.v1.AuthzUserScopesResponseDto;
import com.example.permission.dto.v1.AuthzMeResponseDto;
import com.example.permission.dto.v1.AuthzScopeSummaryDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthorizationQueryService;
import com.example.permission.service.PermissionService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/authz")
public class AuthorizationControllerV1 {

    private static final CacheControl SCOPES_CACHE_CONTROL = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final AuthorizationQueryService authorizationQueryService;
    private final AuthenticatedUserLookupService authenticatedUserLookupService;
    private final PermissionService permissionService;

    public AuthorizationControllerV1(
            AuthorizationQueryService authorizationQueryService,
            AuthenticatedUserLookupService authenticatedUserLookupService,
            PermissionService permissionService
    ) {
        this.authorizationQueryService = authorizationQueryService;
        this.authenticatedUserLookupService = authenticatedUserLookupService;
        this.permissionService = permissionService;
    }

    @GetMapping("/user/{userId}/scopes")
    public ResponseEntity<AuthzUserScopesResponseDto> getUserScopes(@PathVariable Long userId) {
        AuthzUserScopesResponseDto response = authorizationQueryService.getUserScopes(userId);
        if (response.getItems() == null || response.getItems().isEmpty()) {
            // AUTHZ-404: kullanıcıya ait izin/scope ilişkisi bulunamadı
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "AUTHZ-404");
        }
        return ResponseEntity.ok()
                .cacheControl(SCOPES_CACHE_CONTROL)
                .body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<AuthzMeResponseDto> getMe(@AuthenticationPrincipal Jwt jwt) {
        if (jwt == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "JWT missing");
        }
        AuthzMeResponseDto dto = new AuthzMeResponseDto();
        var resolvedUser = authenticatedUserLookupService.resolve(jwt);
        dto.setUserId(resolvedUser.responseUserId());
        dto.setPermissions(resolvePermissions(jwt, resolvedUser.numericUserId()));
        boolean isSuperAdmin = dto.getPermissions().stream().anyMatch(p -> p != null && p.equalsIgnoreCase("admin"));
        dto.setSuperAdmin(isSuperAdmin);

        Map<String, Set<Long>> scopeSummary = resolvedUser.numericUserId() == null
                ? Collections.emptyMap()
                : authorizationQueryService.getUserScopeSummary(resolvedUser.numericUserId());
        List<AuthzScopeSummaryDto> scopes = scopeSummary.entrySet().stream()
                .map(e -> new AuthzScopeSummaryDto(e.getKey(), e.getValue().stream().toList()))
                .collect(Collectors.toList());
        dto.setScopes(scopes);
        List<ScopeSummaryDto> allowedScopes = scopes.stream()
                .flatMap(s -> s.getRefIds().stream().map(id -> new ScopeSummaryDto(s.getScopeType(), id)))
                .toList();
        dto.setAllowedScopes(allowedScopes);

        return ResponseEntity.ok(dto);
    }

    private Set<String> resolvePermissions(Jwt jwt, Long numericUserId) {
        Set<String> jwtPermissions = jwt.getClaimAsStringList("permissions") == null
                ? Set.of()
                : Set.copyOf(jwt.getClaimAsStringList("permissions"));
        if (numericUserId == null) {
            return jwtPermissions;
        }
        Set<String> resolvedPermissions = permissionService.getAssignments(numericUserId, null, null, null).stream()
                .flatMap(assignment -> assignment.getPermissions() == null
                        ? java.util.stream.Stream.empty()
                        : assignment.getPermissions().stream())
                .filter(permission -> permission != null && !permission.isBlank())
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        return resolvedPermissions.isEmpty() ? jwtPermissions : resolvedPermissions;
    }
}

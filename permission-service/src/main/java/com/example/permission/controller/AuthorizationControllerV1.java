package com.example.permission.controller;

import com.example.permission.dto.v1.AuthzUserScopesResponseDto;
import com.example.permission.dto.v1.AuthzMeResponseDto;
import com.example.permission.dto.v1.AuthzScopeSummaryDto;
import com.example.permission.dto.v1.ScopeSummaryDto;
import com.example.permission.service.AuthorizationQueryService;
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
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/authz")
public class AuthorizationControllerV1 {

    private static final CacheControl SCOPES_CACHE_CONTROL = CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final AuthorizationQueryService authorizationQueryService;

    public AuthorizationControllerV1(AuthorizationQueryService authorizationQueryService) {
        this.authorizationQueryService = authorizationQueryService;
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
        dto.setUserId(jwt.getSubject());
        dto.setPermissions(jwt.getClaimAsStringList("permissions") == null ? Set.of() : Set.copyOf(jwt.getClaimAsStringList("permissions")));
        boolean isSuperAdmin = dto.getPermissions().stream().anyMatch(p -> p != null && p.equalsIgnoreCase("admin"));
        dto.setSuperAdmin(isSuperAdmin);

        Long userId;
        try {
            userId = Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "userId parse error");
        }

        var scopeSummary = authorizationQueryService.getUserScopeSummary(userId);
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
}

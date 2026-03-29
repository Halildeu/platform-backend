package com.example.variant.controller;

import com.example.variant.dto.CloneVariantRequest;
import com.example.variant.dto.CreateVariantRequest;
import com.example.variant.dto.ReorderVariantsRequest;
import com.example.variant.dto.UpdateVariantRequest;
import com.example.variant.dto.VariantPreferenceUpdateRequest;
import com.example.variant.dto.VariantResponse;
import com.example.variant.authz.VariantAuthorizationService;
import com.example.variant.security.AuthenticatedUser;
import com.example.variant.security.AuthenticatedUserPrincipal;
import com.example.variant.service.VariantService;
import com.example.commonauth.PermissionCodes;
import com.example.commonauth.AuthorizationContext;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.HashSet;

@RestController
@RequestMapping("/api/variants")
@Deprecated(since = "v1 endpoints added; use /api/v1/variants")
public class VariantController {

    private static final Logger log = LoggerFactory.getLogger(VariantController.class);

    private final VariantService variantService;
    private final VariantAuthorizationService variantAuthorizationService;

    public VariantController(VariantService variantService,
                             VariantAuthorizationService variantAuthorizationService) {
        this.variantService = variantService;
        this.variantAuthorizationService = variantAuthorizationService;
    }

    @GetMapping
    public ResponseEntity<List<VariantResponse>> listVariants(@RequestParam("gridId") String gridId) {
        ResolvedUser resolved = getCurrentUser();
        requirePermission(resolved.authz(), PermissionCodes.VARIANTS_READ);
        return ResponseEntity.ok(variantService.getVariants(resolved.user(), gridId));
    }

    @PostMapping
    public ResponseEntity<VariantResponse> createVariant(@Valid @RequestBody CreateVariantRequest createRequest) {
        ResolvedUser resolved = getCurrentUser();
        requirePermission(resolved.authz(), PermissionCodes.VARIANTS_WRITE);
        VariantResponse response = variantService.createVariant(resolved.user(), createRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PutMapping("/{variantId}")
    public ResponseEntity<VariantResponse> updateVariant(@PathVariable("variantId") UUID variantId,
                                                         @Valid @RequestBody UpdateVariantRequest updateRequest) {
        ResolvedUser resolved = getCurrentUser();
        requirePermission(resolved.authz(), PermissionCodes.VARIANTS_WRITE);
        VariantResponse response = variantService.updateVariant(resolved.user(), variantId, updateRequest);
        return ResponseEntity.ok(response);
    }

    @PatchMapping("/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorderVariants(@Valid @RequestBody ReorderVariantsRequest reorderRequest) {
        ResolvedUser resolved = getCurrentUser();
        requirePermission(resolved.authz(), PermissionCodes.VARIANTS_WRITE);
        variantService.reorderVariants(resolved.user(), reorderRequest);
    }

    @PatchMapping("/{variantId}/preference")
    public ResponseEntity<VariantResponse> updateVariantPreference(@PathVariable("variantId") UUID variantId,
                                                                   @RequestBody VariantPreferenceUpdateRequest preferenceRequest) {
        ResolvedUser resolved = getCurrentUser();
        requirePermission(resolved.authz(), PermissionCodes.VARIANTS_WRITE);
        log.info("[VariantPreference] userId={} variantId={} payload={{isDefault={}, isSelected={}}}",
                resolved.user().id(), variantId, preferenceRequest.getDefault(), preferenceRequest.getSelected());
        VariantResponse response = variantService.updateVariantPreference(resolved.user(), variantId, preferenceRequest);
        log.info("[VariantPreference] userId={} variantId={} result={{isUserDefault={}, isUserSelected={}}}",
                resolved.user().id(), variantId, response.isUserDefault(), response.isUserSelected());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{variantId}/clone")
    public ResponseEntity<VariantResponse> cloneGlobalVariant(@PathVariable("variantId") UUID variantId,
                                                              @RequestBody(required = false) CloneVariantRequest cloneRequest) {
        ResolvedUser resolved = getCurrentUser();
        requirePermission(resolved.authz(), PermissionCodes.VARIANTS_WRITE);
        VariantResponse response = variantService.cloneGlobalVariantToPersonal(
                resolved.user(),
                variantId,
                cloneRequest == null ? new CloneVariantRequest() : cloneRequest
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @DeleteMapping("/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVariant(@PathVariable("variantId") UUID variantId) {
        ResolvedUser resolved = getCurrentUser();
        requirePermission(resolved.authz(), PermissionCodes.VARIANTS_WRITE);
        variantService.deleteVariant(resolved.user(), variantId);
    }

    private ResolvedUser getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        log.info("SecurityContext authentication: {}", authentication);

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması bulunamadı");
        }

        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUserPrincipal authenticatedUserPrincipal) {
            AuthenticatedUser user = authenticatedUserPrincipal.user();
            AuthorizationContext ctx = AuthorizationContext.of(
                    user.id(),
                    user.email(),
                    toRoleSet(user.role()),
                    Set.copyOf(user.permissions()),
                    Set.of(),
                    Set.of(),
                    Set.of()
            );
            return new ResolvedUser(user, ctx);
        }
        if (principal instanceof AuthenticatedUser authenticatedUser) {
            AuthorizationContext ctx = AuthorizationContext.of(
                    authenticatedUser.id(),
                    authenticatedUser.email(),
                    toRoleSet(authenticatedUser.role()),
                    Set.copyOf(authenticatedUser.permissions()),
                    Set.of(),
                    Set.of(),
                    Set.of()
            );
            return new ResolvedUser(authenticatedUser, ctx);
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            AuthorizationContext authz = variantAuthorizationService.buildContext(jwt);
            if (authz.getUserId() == null || authz.getEmail() == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik bilgisi eksik");
            }
            if (!authz.isAdmin() && authz.getPermissions().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "permissions claim eksik veya boş");
            }
            String role = authz.isAdmin() ? "ADMIN" : null;
            AuthenticatedUser user = new AuthenticatedUser(
                    authz.getUserId(),
                    authz.getEmail(),
                    role,
                    authz.getPermissions().stream().toList(),
                    authz.getAllowedProjectIds().stream().map(String::valueOf).toList()
            );
            return new ResolvedUser(user, authz);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması bulunamadı");
    }

    private void requirePermission(AuthorizationContext ctx, String permissionCode) {
        var scope = com.example.commonauth.scope.ScopeContextHolder.get();
        if (scope != null && scope.superAdmin()) {
            return;
        }
        if (ctx.isAdmin()) {
            return;
        }
        if (!ctx.hasPermission(permissionCode)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Gerekli yetki yok: " + permissionCode);
        }
    }

    private Set<String> toRoleSet(String role) {
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        String normalized = role.startsWith("ROLE_") ? role.substring(5) : role;
        return Set.of(normalized);
    }

    private record ResolvedUser(AuthenticatedUser user, AuthorizationContext authz) {
    }
}

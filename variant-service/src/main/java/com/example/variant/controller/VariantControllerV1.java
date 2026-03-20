package com.example.variant.controller;

import com.example.variant.dto.VariantResponse;
import com.example.variant.dto.v1.CloneVariantRequestDto;
import com.example.variant.dto.v1.CreateVariantRequestDto;
import com.example.variant.dto.v1.PagedResultDto;
import com.example.variant.dto.v1.ReorderVariantsRequestDto;
import com.example.variant.dto.v1.UpdateVariantRequestDto;
import com.example.variant.dto.v1.VariantDto;
import com.example.variant.dto.v1.VariantDtoMapper;
import com.example.variant.dto.v1.VariantPreferenceUpdateRequestDto;
import com.example.variant.dto.v1.VariantPresetListResponse;
import com.example.variant.authz.VariantAuthorizationService;
import com.example.variant.security.AuthenticatedUser;
import com.example.variant.security.AuthenticatedUserPrincipal;
import com.example.variant.service.VariantService;
import com.example.variant.service.VariantPresetService;
import com.example.commonauth.AuthorizationContext;
import com.example.commonauth.PermissionCodes;
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

/**
 * v1 REST path'leri; legacy VariantController korunur.
 */
@RestController
@RequestMapping("/api/v1/variants")
public class VariantControllerV1 {

    private static final Logger log = LoggerFactory.getLogger(VariantControllerV1.class);

    private final VariantService variantService;
    private final VariantAuthorizationService variantAuthorizationService;
    private final VariantPresetService variantPresetService;

    public VariantControllerV1(VariantService variantService,
                               VariantAuthorizationService variantAuthorizationService,
                               VariantPresetService variantPresetService) {
        this.variantService = variantService;
        this.variantAuthorizationService = variantAuthorizationService;
        this.variantPresetService = variantPresetService;
    }

    @GetMapping
    public ResponseEntity<PagedResultDto<VariantDto>> listVariants(@RequestParam("gridId") String gridId) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_READ);
        List<VariantResponse> variants = variantService.getVariants(resolvedUser.user(), gridId);
        List<VariantDto> items = variants.stream().map(VariantDtoMapper::toDto).toList();
        return ResponseEntity.ok(new PagedResultDto<>(items, items.size(), null, null));
    }

    @GetMapping("/presets")
    public ResponseEntity<VariantPresetListResponse> listPresets(@RequestParam("gridId") String gridId,
                                                                 @RequestParam(value = "companyId", required = false) String companyId) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_READ);
        VariantPresetListResponse response = variantPresetService.listPresets(gridId, companyId, resolvedUser.user().id());
        return ResponseEntity.ok(response);
    }

    @PostMapping
    public ResponseEntity<VariantDto> createVariant(@Valid @RequestBody CreateVariantRequestDto createRequest) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_WRITE);
        VariantResponse response = variantService.createVariant(resolvedUser.user(), createRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(VariantDtoMapper.toDto(response));
    }

    @PutMapping("/{variantId}")
    public ResponseEntity<VariantDto> updateVariant(@PathVariable("variantId") UUID variantId,
                                                    @Valid @RequestBody UpdateVariantRequestDto updateRequest) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_WRITE);
        VariantResponse response = variantService.updateVariant(resolvedUser.user(), variantId, updateRequest);
        return ResponseEntity.ok(VariantDtoMapper.toDto(response));
    }

    @PatchMapping("/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reorderVariants(@Valid @RequestBody ReorderVariantsRequestDto reorderRequest) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_WRITE);
        variantService.reorderVariants(resolvedUser.user(), reorderRequest);
    }

    @PatchMapping("/{variantId}/preference")
    public ResponseEntity<VariantDto> updateVariantPreference(@PathVariable("variantId") UUID variantId,
                                                              @RequestBody VariantPreferenceUpdateRequestDto preferenceRequest) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_WRITE);
        log.info("[VariantPreference v1] userId={} variantId={} payload={{isDefault={}, isSelected={}}}",
                resolvedUser.user().id(), variantId, preferenceRequest.getDefault(), preferenceRequest.getSelected());
        VariantResponse response = variantService.updateVariantPreference(resolvedUser.user(), variantId, preferenceRequest);
        log.info("[VariantPreference v1] userId={} variantId={} result={{isUserDefault={}, isUserSelected={}}}",
                resolvedUser.user().id(), variantId, response.isUserDefault(), response.isUserSelected());
        return ResponseEntity.ok(VariantDtoMapper.toDto(response));
    }

    @PostMapping("/{variantId}/clone")
    public ResponseEntity<VariantDto> cloneGlobalVariant(@PathVariable("variantId") UUID variantId,
                                                         @RequestBody(required = false) CloneVariantRequestDto cloneRequest) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_WRITE);
        CloneVariantRequestDto payload = cloneRequest == null ? new CloneVariantRequestDto() : cloneRequest;
        VariantResponse response = variantService.cloneGlobalVariantToPersonal(
                resolvedUser.user(),
                variantId,
                payload
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(VariantDtoMapper.toDto(response));
    }

    @DeleteMapping("/{variantId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteVariant(@PathVariable("variantId") UUID variantId) {
        ResolvedUser resolvedUser = getCurrentUser();
        requirePermission(resolvedUser.authz(), PermissionCodes.VARIANTS_WRITE);
        variantService.deleteVariant(resolvedUser.user(), variantId);
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
            log.info("Resolved variant authz context: userId={} email={} roles={} permissionsCount={} isAdmin={}",
                    authz.getUserId(),
                    authz.getEmail(),
                    authz.getRoles(),
                    authz.getPermissions() == null ? 0 : authz.getPermissions().size(),
                    authz.isAdmin());
            if (authz.getUserId() == null || authz.getEmail() == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik bilgisi eksik");
            }
            if (!authz.isAdmin() && authz.getPermissions().isEmpty()) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "permissions claim eksik veya boş");
            }
            // Not: Variant global bir katalog; company/project data-scope uygulanmıyor.
            // /authz/me yalnız permissions set’i için kullanılıyor.
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

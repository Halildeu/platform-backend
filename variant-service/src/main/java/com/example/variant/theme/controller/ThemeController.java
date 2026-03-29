package com.example.variant.theme.controller;

import com.example.commonauth.AuthorizationContext;
import com.example.commonauth.PermissionCodes;
import com.example.variant.authz.VariantAuthorizationService;
import com.example.variant.security.AuthenticatedUser;
import com.example.variant.security.AuthenticatedUserPrincipal;
import com.example.variant.theme.domain.Theme;
import com.example.variant.theme.service.ResolvedTheme;
import com.example.variant.theme.service.ThemeService;
import com.example.variant.theme.service.ThemeValidationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.security.Principal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ThemeController {

    private final ThemeService themeService;
    private final VariantAuthorizationService variantAuthorizationService;

    public ThemeController(ThemeService themeService,
                           VariantAuthorizationService variantAuthorizationService) {
        this.themeService = themeService;
        this.variantAuthorizationService = variantAuthorizationService;
    }

    private String requireCurrentUserId(Principal principal) {
        if (principal != null && principal.getName() != null && !principal.getName().isBlank()) {
            return principal.getName();
        }
        AuthorizationContext ctx = getAuthorizationContext();
        if (ctx.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik bilgisi eksik");
        }
        return ctx.getUserId().toString();
    }

    @GetMapping("/themes")
    public List<Theme> listThemes(@RequestParam(name = "scope", defaultValue = "global") String scope,
                                  Principal principal) {
        if ("user".equalsIgnoreCase(scope)) {
            String userId = requireCurrentUserId(principal);
            return themeService.listUserThemes(userId);
        }
        return themeService.listGlobalThemes();
    }

    @GetMapping("/themes/{id}")
    public Theme getTheme(@PathVariable("id") UUID id, Principal principal) {
        String ownerId = principal != null ? principal.getName() : null;
        return themeService.getTheme(id, ownerId);
    }

    @PutMapping("/themes/global/{id}")
    public Theme updateGlobalTheme(@PathVariable("id") UUID id,
                                   @RequestBody Map<String, String> overrides) {
        AuthorizationContext ctx = getAuthorizationContext();
        requireThemeAdmin(ctx);
        return themeService.updateGlobalTheme(id, overrides);
    }

    public static final class UpdateGlobalThemeMetaRequest {

        private String appearance;
        private String surfaceTone;
        private ThemeAxesRequest axes;
        private Boolean activeFlag;

        public String getAppearance() {
            return appearance;
        }

        public void setAppearance(String appearance) {
            this.appearance = appearance;
        }

        public String getSurfaceTone() {
            return surfaceTone;
        }

        public void setSurfaceTone(String surfaceTone) {
            this.surfaceTone = surfaceTone;
        }

        public ThemeAxesRequest getAxes() {
            return axes;
        }

        public void setAxes(ThemeAxesRequest axes) {
            this.axes = axes;
        }

        public Boolean getActiveFlag() {
            return activeFlag;
        }

        public void setActiveFlag(Boolean activeFlag) {
            this.activeFlag = activeFlag;
        }

        public static final class ThemeAxesRequest {

            private String accent;
            private String density;
            private String radius;
            private String elevation;
            private String motion;

            public String getAccent() {
                return accent;
            }

            public void setAccent(String accent) {
                this.accent = accent;
            }

            public String getDensity() {
                return density;
            }

            public void setDensity(String density) {
                this.density = density;
            }

            public String getRadius() {
                return radius;
            }

            public void setRadius(String radius) {
                this.radius = radius;
            }

            public String getElevation() {
                return elevation;
            }

            public void setElevation(String elevation) {
                this.elevation = elevation;
            }

            public String getMotion() {
                return motion;
            }

            public void setMotion(String motion) {
                this.motion = motion;
            }
        }
    }

    public static final class UpdateGlobalThemePaletteRequest {

        private List<ThemePaletteItem> themes;

        public List<ThemePaletteItem> getThemes() {
            return themes;
        }

        public void setThemes(List<ThemePaletteItem> themes) {
            this.themes = themes;
        }

        public static final class ThemePaletteItem {

            private UUID id;
            private Boolean activeFlag;

            public UUID getId() {
                return id;
            }

            public void setId(UUID id) {
                this.id = id;
            }

            public Boolean getActiveFlag() {
                return activeFlag;
            }

            public void setActiveFlag(Boolean activeFlag) {
                this.activeFlag = activeFlag;
            }
        }
    }

    @PutMapping("/themes/global/{id}/meta")
    public Theme updateGlobalThemeMeta(@PathVariable("id") UUID id,
                                       @RequestBody UpdateGlobalThemeMetaRequest request) {
        AuthorizationContext ctx = getAuthorizationContext();
        requireThemeAdmin(ctx);
        UpdateGlobalThemeMetaRequest.ThemeAxesRequest axes = request != null ? request.getAxes() : null;
        return themeService.updateGlobalThemeMeta(
            id,
            request != null ? request.getAppearance() : null,
            request != null ? request.getSurfaceTone() : null,
            axes != null ? axes.getAccent() : null,
            axes != null ? axes.getDensity() : null,
            axes != null ? axes.getRadius() : null,
            axes != null ? axes.getElevation() : null,
            axes != null ? axes.getMotion() : null,
            request != null ? request.getActiveFlag() : null
        );
    }

    @PutMapping("/themes/global/palette")
    public List<Theme> updateGlobalThemePalette(@RequestBody UpdateGlobalThemePaletteRequest request) {
        AuthorizationContext ctx = getAuthorizationContext();
        requireThemeAdmin(ctx);
        if (request == null || request.getThemes() == null || request.getThemes().isEmpty()) {
            throw new ThemeValidationException("PALETTE_ITEMS_REQUIRED");
        }

        Map<UUID, Boolean> activeFlagsById = new HashMap<>();
        for (UpdateGlobalThemePaletteRequest.ThemePaletteItem item : request.getThemes()) {
            if (item == null || item.getId() == null) {
                continue;
            }
            if (item.getActiveFlag() == null) {
                throw new ThemeValidationException("PALETTE_ACTIVE_FLAG_REQUIRED");
            }
            activeFlagsById.put(item.getId(), item.getActiveFlag());
        }

        if (activeFlagsById.isEmpty()) {
            throw new ThemeValidationException("PALETTE_ITEMS_REQUIRED");
        }

        return themeService.updateGlobalThemePalette(activeFlagsById);
    }

    @PutMapping("/themes/global/default/{id}")
    public Theme setGlobalDefaultTheme(@PathVariable("id") UUID id) {
        AuthorizationContext ctx = getAuthorizationContext();
        requireThemeAdmin(ctx);
        return themeService.setGlobalDefaultTheme(id);
    }

    @PostMapping("/themes")
    public ResponseEntity<Theme> createUserTheme(@RequestBody Theme theme, Principal principal) {
        String userId = requireCurrentUserId(principal);
        Theme created = themeService.createUserTheme(theme, userId);
        return ResponseEntity
            .created(URI.create("/api/v1/themes/" + created.getId()))
            .body(created);
    }

    @PutMapping("/themes/{id}")
    public Theme updateUserTheme(@PathVariable("id") UUID id,
                                 @RequestBody Map<String, String> overrides,
                                 Principal principal) {
        String userId = requireCurrentUserId(principal);
        return themeService.updateUserTheme(id, userId, overrides);
    }

    @DeleteMapping("/themes/{id}")
    public ResponseEntity<Void> deleteUserTheme(@PathVariable("id") UUID id, Principal principal) {
        String userId = requireCurrentUserId(principal);
        themeService.deleteUserTheme(id, userId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/themes/{id}/fork")
    public Theme forkTheme(@PathVariable("id") UUID id, Principal principal) {
        String userId = requireCurrentUserId(principal);
        return themeService.forkFromGlobal(id, userId);
    }

    @GetMapping("/me/theme/resolved")
    public ResponseEntity<ResolvedTheme> getResolvedTheme(
        Principal principal,
        @RequestHeader(name = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch
    ) {
        String userId = requireCurrentUserId(principal);
        ResolvedTheme resolved = themeService.resolveThemeForUser(userId);

        String version = resolved.getVersion();
        String etag = version != null && !version.isBlank() ? "\"" + version + "\"" : null;
        if (etag != null && isIfNoneMatchSatisfied(ifNoneMatch, etag)) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).eTag(etag).build();
        }
        if (etag != null) {
            return ResponseEntity.ok().eTag(etag).body(resolved);
        }
        return ResponseEntity.ok(resolved);
    }

    public static final class SetMyThemeRequest {

        private String themeId;

        public String getThemeId() {
            return themeId;
        }

        public void setThemeId(String themeId) {
            this.themeId = themeId;
        }
    }

    @PatchMapping("/me/theme")
    public ResponseEntity<Void> setMyTheme(@RequestBody SetMyThemeRequest request, Principal principal) {
        if (request == null || request.getThemeId() == null || request.getThemeId().isBlank()) {
            throw new ThemeValidationException("THEME_ID_REQUIRED");
        }
        UUID themeId;
        try {
            themeId = UUID.fromString(request.getThemeId());
        } catch (IllegalArgumentException ex) {
            throw new ThemeValidationException("INVALID_THEME_ID");
        }
        String userId = requireCurrentUserId(principal);
        themeService.selectUserTheme(userId, themeId);
        return ResponseEntity.noContent().build();
    }

    private AuthorizationContext getAuthorizationContext() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması bulunamadı");
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof AuthenticatedUserPrincipal authenticatedUserPrincipal) {
            AuthenticatedUser user = authenticatedUserPrincipal.user();
            return AuthorizationContext.of(
                user.id(),
                user.email(),
                toRoleSet(user.role()),
                Set.copyOf(user.permissions()),
                Set.of(),
                Set.of(),
                Set.of()
            );
        }
        if (principal instanceof AuthenticatedUser user) {
            return AuthorizationContext.of(
                user.id(),
                user.email(),
                toRoleSet(user.role()),
                Set.copyOf(user.permissions()),
                Set.of(),
                Set.of(),
                Set.of()
            );
        }
        if (authentication instanceof JwtAuthenticationToken jwtAuth) {
            Jwt jwt = jwtAuth.getToken();
            AuthorizationContext ctx = variantAuthorizationService.buildContext(jwt);
            return ctx;
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Kimlik doğrulaması bulunamadı");
    }

    private void requireThemeAdmin(AuthorizationContext ctx) {
        var scope = com.example.commonauth.scope.ScopeContextHolder.get();
        if (scope != null && scope.superAdmin()) {
            return;
        }
        if (ctx.isAdmin()) {
            return;
        }
        if (ctx.hasPermission(PermissionCodes.THEME_ADMIN) || ctx.hasPermission(PermissionCodes.SYSTEM_CONFIGURE)) {
            return;
        }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN, "THEME_ADMIN permission required");
    }

    private Set<String> toRoleSet(String role) {
        if (role == null || role.isBlank()) {
            return Set.of();
        }
        String normalized = role.startsWith("ROLE_") ? role.substring(5) : role;
        return Set.of(normalized);
    }

    private boolean isIfNoneMatchSatisfied(String ifNoneMatchHeader, String currentEtag) {
        if (ifNoneMatchHeader == null || ifNoneMatchHeader.isBlank() || currentEtag == null) {
            return false;
        }
        String raw = ifNoneMatchHeader.trim();
        if ("*".equals(raw)) {
            return true;
        }
        String[] parts = raw.split(",");
        for (String part : parts) {
            if (currentEtag.equals(part.trim())) {
                return true;
            }
        }
        return false;
    }
}

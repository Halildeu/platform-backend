package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.RequireModule;
import com.example.permission.service.AuthenticatedUserLookupService;
import com.example.permission.service.AuthenticatedUserLookupService.ResolvedAuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

/**
 * ADR-0012 Phase 3: HandlerInterceptor that enforces @RequireModule via OpenFGA.
 *
 * Register in WebMvcConfig: registry.addInterceptor(new RequireModuleInterceptor(authzService, lookupService))
 *
 * When OpenFGA is enabled:
 *   Extracts numeric userId from JWT (via AuthenticatedUserLookupService — same path as
 *   /api/v1/authz/me, ensures tuples seeded with numeric IDs match) → calls
 *   OpenFGA check(userId, mappedRelation, "module", moduleName)
 *   Denied → returns 403 Forbidden
 *
 * When OpenFGA is disabled (dev mode):
 *   All checks pass (consistent with OpenFgaAuthzService dev-mode behavior)
 *
 * Relation alias mapping (2026-04-29 fix — D35-3 ladder closure cross-repo bug):
 *   Annotations historically used "viewer"/"manager"/"admin" labels. The deployed
 *   OpenFGA module type only defines `can_view`, `can_manage`, `can_edit`, `blocked`
 *   (per `backend/openfga/model.fga`). This interceptor maps legacy aliases to the
 *   canonical model relations so existing annotations keep working while controllers
 *   migrate to explicit `can_view`/`can_manage` form.
 */
public class RequireModuleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequireModuleInterceptor.class);

    /**
     * Maps legacy annotation relation aliases to OpenFGA module model relations.
     *
     * Background: ADR-0012 Phase 3 introduced @RequireModule with example values
     * "viewer"/"manager"/"admin", but the OpenFGA `module` type only declares
     * `can_view`/`can_manage`/`can_edit`/`blocked`. This map prevents HTTP 400
     * `relation 'module#X' not found` errors that previously caused all guarded
     * endpoints to fail-closed.
     *
     * Canonical names (`can_view`, `can_manage`, `can_edit`, `blocked`) pass through
     * unchanged. Unknown aliases are passed through verbatim (OpenFGA will surface
     * the validation error for visibility).
     */
    static final Map<String, String> RELATION_ALIASES = Map.of(
            "viewer", "can_view",
            "manager", "can_manage",
            "admin", "can_manage",
            "editor", "can_edit"
    );

    private final OpenFgaAuthzService authzService;
    private final AuthenticatedUserLookupService userLookupService;

    public RequireModuleInterceptor(OpenFgaAuthzService authzService,
                                    @Nullable AuthenticatedUserLookupService userLookupService) {
        this.authzService = authzService;
        this.userLookupService = userLookupService;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireModule annotation = handlerMethod.getMethodAnnotation(RequireModule.class);
        if (annotation == null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequireModule.class);
        }
        if (annotation == null) {
            return true;
        }

        if (!authzService.isEnabled()) {
            return true;
        }

        String userId = extractUserId();
        if (userId == null) {
            log.warn("RequireModule check — no authenticated user for {}", handlerMethod.getMethod().getName());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Authentication required\"}");
            return false;
        }

        String module = annotation.value();
        String declaredRelation = annotation.relation();
        String resolvedRelation = mapToOpenFgaRelation(declaredRelation);

        // 2026-04-29: superAdmin bypass — `organization:default#admin` tuple
        // sahibi (organization-level admin) tüm modül guard'larından geçer.
        // Bu, /api/v1/authz/me'nin checkOrganizationAdmin pattern'i ile uyumlu;
        // iki authz path eşitlenir. D35-3 closure flow'unda iki path arası
        // tutarsızlık tespit edildi (frontend superAdmin: true gösteriyor ama
        // RequireModule guard her modül için ayrı tuple arıyordu).
        if (isOrganizationAdmin(userId)) {
            log.debug("RequireModule SUPER-ADMIN BYPASS: user={} module={} relation={} (declared={}) — organization:default#admin",
                    userId, module, resolvedRelation, declaredRelation);
            return true;
        }

        boolean allowed = authzService.check(userId, resolvedRelation, "module", module);

        if (!allowed) {
            log.info("authz.decision user={} relation={} (declared={}) object=module:{} allowed=false source=RequireModule",
                    userId, resolvedRelation, declaredRelation, module);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Access denied: module=" + module + " relation=" + resolvedRelation + "\"}");
            return false;
        }

        log.debug("RequireModule PASS: user={} module={} relation={} (declared={})", userId, module, resolvedRelation, declaredRelation);
        return true;
    }

    /**
     * Checks if the user is organization-level admin (mirrors AuthorizationControllerV1.checkOrganizationAdmin).
     * Uses OpenFGA tuple `user:<id>` `admin` `organization:default`. Failures pass
     * through to module-level check (fail-open by skipping bypass; fail-closed at module level).
     */
    boolean isOrganizationAdmin(String userId) {
        try {
            return authzService.check(userId, "admin", "organization", "default");
        } catch (RuntimeException ex) {
            log.warn("RequireModule organization admin check failed (fall through to module check): user={} cause={}",
                    userId, ex.getMessage());
            return false;
        }
    }

    /**
     * Maps a declared annotation relation to the OpenFGA model relation.
     * Returns the canonical relation if the input is an alias; otherwise returns
     * the input unchanged (passthrough for `can_view`/`can_manage`/`can_edit` etc.).
     */
    static String mapToOpenFgaRelation(String declaredRelation) {
        if (declaredRelation == null) {
            return null;
        }
        return RELATION_ALIASES.getOrDefault(declaredRelation, declaredRelation);
    }

    String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            // Prefer the same numeric resolution path as /api/v1/authz/me
            // (AuthenticatedUserLookupService): userId/uid claim → sub.parseLong → email lookup.
            // This matches the user identifier OpenFGA tuples are seeded with (numeric).
            if (userLookupService != null) {
                ResolvedAuthenticatedUser resolved = userLookupService.resolve(jwt);
                if (resolved != null) {
                    Long numericUserId = resolved.numericUserId();
                    if (numericUserId != null) {
                        return Long.toString(numericUserId);
                    }
                    String responseUserId = resolved.responseUserId();
                    if (responseUserId != null && !responseUserId.isBlank()) {
                        return responseUserId;
                    }
                }
            }

            // Fallback: legacy claim extraction (kept for environments where lookup
            // service is unavailable, e.g. lightweight tests).
            Object userIdClaim = jwt.getClaim("userId");
            if (userIdClaim != null) {
                return String.valueOf(userIdClaim);
            }
            return jwt.getSubject();
        }
        if (principal instanceof String s && !"anonymousUser".equals(s)) {
            return s;
        }
        return null;
    }
}

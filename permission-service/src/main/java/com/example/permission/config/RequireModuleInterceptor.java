package com.example.permission.config;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.RequireModule;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * ADR-0012 Phase 3: HandlerInterceptor that enforces @RequireModule via OpenFGA.
 *
 * Register in WebMvcConfig: registry.addInterceptor(new RequireModuleInterceptor(authzService))
 *
 * When OpenFGA is enabled:
 *   Extracts userId from JWT → calls OpenFGA check(userId, relation, "module", moduleName)
 *   Denied → returns 403 Forbidden
 *
 * When OpenFGA is disabled (dev mode):
 *   All checks pass (consistent with OpenFgaAuthzService dev-mode behavior)
 */
public class RequireModuleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RequireModuleInterceptor.class);

    private final OpenFgaAuthzService authzService;

    public RequireModuleInterceptor(OpenFgaAuthzService authzService) {
        this.authzService = authzService;
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
        String relation = annotation.relation();

        boolean allowed = authzService.check(userId, relation, "module", module);

        if (!allowed) {
            log.info("authz.decision user={} relation={} object=module:{} allowed=false source=RequireModule",
                    userId, relation, module);
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Access denied: module=" + module + " relation=" + relation + "\"}");
            return false;
        }

        log.debug("RequireModule PASS: user={} module={} relation={}", userId, module, relation);
        return true;
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
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

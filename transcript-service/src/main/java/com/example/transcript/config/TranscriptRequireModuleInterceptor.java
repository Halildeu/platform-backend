package com.example.transcript.config;

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
 * Module-level OpenFGA authorization interceptor for {@code @RequireModule}
 * handlers. Mirrors {@code EndpointAdminRequireModuleInterceptor}.
 *
 * <p>Fail-OPEN no-op when OpenFGA is disabled (local/dev/test — the gate is
 * intentionally inert so a pod stays green without an OpenFGA backend).
 * Fail-CLOSED when enabled: missing principal → 401, denied check → 403, and
 * {@link OpenFgaAuthzService#check} itself fails-closed (deny) on backend error.
 *
 * <p>{@link #extractUserId()} uses the SAME {@code userId}-claim-or-{@code sub}
 * precedence as {@code JwtTenantContextResolver}, so the principal the gate
 * checks is exactly the principal recorded in the KVKK m.12 access audit.
 */
public class TranscriptRequireModuleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TranscriptRequireModuleInterceptor.class);

    private final OpenFgaAuthzService authzService;

    public TranscriptRequireModuleInterceptor(OpenFgaAuthzService authzService) {
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
        if (annotation == null || !authzService.isEnabled()) {
            return true;
        }

        String userId = extractUserId();
        if (userId == null) {
            log.warn("transcript authz denied: missing authenticated user for {}", handlerMethod.getMethod().getName());
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"unauthorized\",\"message\":\"Authentication required\"}");
            return false;
        }

        boolean allowed = authzService.check(userId, annotation.relation(), "module", annotation.value());
        if (!allowed) {
            log.info("transcript.authz user={} relation={} object=module:{} allowed=false",
                    userId, annotation.relation(), annotation.value());
            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
            response.setContentType("application/json");
            response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"Access denied\"}");
            return false;
        }

        return true;
    }

    private String extractUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Object principal = authentication.getPrincipal();
        if (principal instanceof Jwt jwt) {
            Object userIdClaim = jwt.getClaim("userId");
            if (userIdClaim != null) {
                return String.valueOf(userIdClaim);
            }
            return jwt.getSubject();
        }
        if (principal instanceof String value && !"anonymousUser".equals(value)) {
            return value;
        }
        return null;
    }
}

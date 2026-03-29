package com.example.commonauth.scope;

import com.example.commonauth.openfga.OpenFgaAuthzService;
import com.example.commonauth.openfga.OpenFgaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Set;

/**
 * Servlet filter that populates {@link ScopeContextHolder} on every request.
 *
 * Production (openfga.enabled=true):
 *   JWT → extract userId → OpenFGA listObjects → ScopeContext
 *
 * Dev/permitAll (openfga.enabled=false):
 *   YAML config → ScopeContext with static dev scope IDs
 *
 * Always clears the context after the request completes (finally block).
 */
public class ScopeContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScopeContextFilter.class);

    private final OpenFgaAuthzService authzService;
    private final OpenFgaProperties properties;

    public ScopeContextFilter(OpenFgaAuthzService authzService, OpenFgaProperties properties) {
        this.authzService = authzService;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        try {
            ScopeContext ctx = buildScopeContext(request);
            ScopeContextHolder.set(ctx);
            log.debug("ScopeContext set: userId={}, companies={}, superAdmin={}",
                    ctx.userId(), ctx.allowedCompanyIds(), ctx.superAdmin());
            filterChain.doFilter(request, response);
        } finally {
            ScopeContextHolder.clear();
        }
    }

    private ScopeContext buildScopeContext(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return buildDevScopeContext();
        }

        String userId = extractUserId();
        if (userId == null) {
            log.debug("No authenticated user — returning empty scope");
            return ScopeContext.empty(null);
        }

        try {
            Set<Long> companyIds = authzService.listObjectIds(userId, "viewer", "company");
            Set<Long> projectIds = authzService.listObjectIds(userId, "viewer", "project");
            Set<Long> warehouseIds = authzService.listObjectIds(userId, "viewer", "warehouse");

            boolean isSuperAdmin = authzService.check(userId, "admin", "organization", "default");

            if (isSuperAdmin) {
                return ScopeContext.superAdmin(userId);
            }

            return new ScopeContext(userId, companyIds, projectIds, warehouseIds, false);
        } catch (Exception e) {
            log.error("Failed to build ScopeContext from OpenFGA for user {}, falling back to dev scope", userId, e);
            return buildDevScopeContext();
        }
    }

    private ScopeContext buildDevScopeContext() {
        OpenFgaProperties.DevScope dev = properties.getDevScope();
        return new ScopeContext(
                "dev-user",
                dev.getCompanyIds(),
                dev.getProjectIds(),
                dev.getWarehouseIds(),
                dev.isSuperAdmin()
        );
    }

    private String extractUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        Object principal = auth.getPrincipal();
        if (principal instanceof Jwt jwt) {
            // Try numeric userId claim first, then sub
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

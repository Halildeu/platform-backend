package com.example.commonauth.scope;

import com.example.commonauth.AuthenticatedUserLookupService;
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

/**
 * Servlet filter that populates {@link ScopeContextHolder} on every request.
 *
 * Production (openfga.enabled=true):
 *   JWT → extract userId → {@link OpenFgaScopeReader} → ScopeContext
 *
 * Dev/permitAll (openfga.enabled=false):
 *   YAML config → ScopeContext with static dev scope IDs
 *
 * Always clears the context after the request completes (finally block).
 *
 * <p>Codex thread 019e0891 iter-2 AGREE absorb (PR-BE-10 Phase 3): the
 * OpenFGA fetch logic was extracted into {@link OpenFgaScopeReader} so
 * permission-service controllers (which do NOT register this filter)
 * can read the same authoritative scope view via the same parallel
 * fetch + cache + relation map.
 */
public class ScopeContextFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ScopeContextFilter.class);

    private final OpenFgaProperties properties;
    private final AuthenticatedUserLookupService authenticatedUserLookupService;
    private final OpenFgaScopeReader scopeReader;

    public ScopeContextFilter(OpenFgaAuthzService authzService, OpenFgaProperties properties) {
        this(authzService, properties, null, null, null);
    }

    public ScopeContextFilter(OpenFgaAuthzService authzService,
                              OpenFgaProperties properties,
                              AuthenticatedUserLookupService authenticatedUserLookupService) {
        this(authzService, properties, authenticatedUserLookupService, null, null);
    }

    public ScopeContextFilter(OpenFgaAuthzService authzService,
                              OpenFgaProperties properties,
                              AuthenticatedUserLookupService authenticatedUserLookupService,
                              ScopeContextCache scopeContextCache,
                              AuthzVersionProvider versionProvider) {
        this.properties = properties;
        this.authenticatedUserLookupService = authenticatedUserLookupService;
        this.scopeReader = new OpenFgaScopeReader(
                authzService, properties, scopeContextCache, versionProvider);
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

        // OpenFgaScopeReader handles cache + parallel fetch internally and
        // propagates exceptions. In production an authorization dependency
        // failure is not evidence of access: keep the authenticated identity
        // for diagnostics/RLS but grant no company/project/warehouse scope.
        // Dev YAML scope remains available only when OpenFGA is explicitly
        // disabled above.
        try {
            return scopeReader.readScopeContext(userId, extractKcSubjectAlias());
        } catch (Exception e) {
            log.error("Failed to build ScopeContext from OpenFGA for user {}; failing closed", userId, e);
            return ScopeContext.empty(userId);
        }
    }

    /**
     * The product grant path keys OpenFGA tuples by the Keycloak subject
     * (data_access scope outbox writes {@code user:<kc-uuid>} unconditionally),
     * while {@link #extractUserId()} prefers the resolved numeric platform id
     * once the principal exists in users_db. Reading with only the numeric
     * subject makes every UUID-keyed tuple invisible the moment the numeric
     * identity appears (#2530 family; measured live on gitops#3468: the same
     * token read company scope fine before its users_db row existed and lost
     * it right after). The reader's dual-subject overload (#2531 mirror)
     * exists precisely for this; it ignores a blank alias or one equal to the
     * primary id, so the extra argument is a no-op for principals whose
     * primary id already is the KC subject.
     */
    private String extractKcSubjectAlias() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return null;
        }
        return auth.getPrincipal() instanceof Jwt jwt ? jwt.getSubject() : null;
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
            if (authenticatedUserLookupService != null) {
                var resolved = authenticatedUserLookupService.resolve(jwt);
                if (resolved.responseUserId() != null) {
                    return resolved.responseUserId();
                }
            }
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

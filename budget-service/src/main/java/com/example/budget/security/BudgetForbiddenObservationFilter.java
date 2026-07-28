package com.example.budget.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

public class BudgetForbiddenObservationFilter extends OncePerRequestFilter {
    private static final Logger log =
            LoggerFactory.getLogger(BudgetForbiddenObservationFilter.class);
    private static final String PROJECTS_PATH = "/api/v1/budgets/projects";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        Authentication authentication =
                SecurityContextHolder.getContext().getAuthentication();
        Jwt jwt = authentication != null
                        && authentication.isAuthenticated()
                        && authentication.getPrincipal() instanceof Jwt principal
                ? principal
                : null;

        filterChain.doFilter(request, response);

        if (response.getStatus() != HttpServletResponse.SC_FORBIDDEN
                || !isProjectPath(request.getRequestURI())
                || jwt == null) {
            return;
        }

        log.warn(
                "budget_request_forbidden method={} route={} scopeRead={} scopeWrite={} "
                        + "scopeApprove={} rolePlanner={} roleApprover={} subjectPresent={} "
                        + "tenantClaimPresent={} orgClaimPresent={}",
                request.getMethod(),
                routeCategory(request.getRequestURI()),
                hasAuthority(authentication, "SCOPE_budget:read"),
                hasAuthority(authentication, "SCOPE_budget:write"),
                hasAuthority(authentication, "SCOPE_budget:approve"),
                hasAuthority(authentication, "ROLE_BUDGET_PLANNER"),
                hasAuthority(authentication, "ROLE_BUDGET_APPROVER"),
                present(jwt.getSubject()),
                present(jwt.getClaimAsString("tenant_id")),
                present(jwt.getClaimAsString("org_id")));
    }

    private boolean hasAuthority(Authentication authentication, String expected) {
        return authentication.getAuthorities().stream()
                .anyMatch(authority -> expected.equals(authority.getAuthority()));
    }

    private boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isProjectPath(String requestUri) {
        return PROJECTS_PATH.equals(requestUri)
                || requestUri.startsWith(PROJECTS_PATH + "/");
    }

    static String routeCategory(String requestUri) {
        if ((PROJECTS_PATH + "/bindings").equals(requestUri)) {
            return "project_bindings";
        }
        if ((PROJECTS_PATH + "/cost-rules").equals(requestUri)) {
            return "project_cost_rules";
        }
        if (PROJECTS_PATH.equals(requestUri)) {
            return "project_binding_create";
        }
        if (requestUri.endsWith("/actuals/sync")) {
            return "project_actuals_sync";
        }
        if (requestUri.endsWith("/actuals/summary")) {
            return "project_actuals_summary";
        }
        if (requestUri.endsWith("/actuals")) {
            return "project_actuals_rows";
        }
        return "project_other";
    }
}

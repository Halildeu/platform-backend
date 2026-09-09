package com.example.schema.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;

/**
 * Applies {@link ReportModuleAccessGate} to every JWT-authenticated call under
 * {@code /api/v1/schema/**} (gitops#3608).
 *
 * <p>Before this the security chain only asked "is the JWT valid?", so any
 * authenticated user could read the whole catalogue. Requests that reach a
 * handler <em>without</em> a JWT principal are the in-cluster paths the
 * SecurityConfig permits ({@code /snapshot}, {@code /reporting-contract},
 * {@code /master-data/**}); those keep their controller-side
 * {@code X-Internal-Api-Key} guard and are not module-gated here.
 *
 * <p>The OpenFGA subject is the numeric {@code userId} (or {@code uid}) claim the
 * frontend also uses; a JWT without it cannot be mapped to a tuple and is
 * refused rather than guessed from {@code sub}.
 */
public class ReportModuleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ReportModuleInterceptor.class);
    private static final String[] USER_ID_CLAIMS = {"userId", "uid"};

    private final ReportModuleAccessGate gate;

    public ReportModuleInterceptor(ReportModuleAccessGate gate) {
        this.gate = gate;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        Jwt jwt = authenticatedJwt();
        if (jwt == null) {
            return true;
        }
        String userId = numericUserId(jwt);
        ReportModuleAccessGate.Decision decision = gate.decide(userId);
        if (decision.allowed()) {
            return true;
        }
        log.info("schema.authz denied user={} path={} reason={}", userId, request.getRequestURI(), decision.reason());
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"error\":\"forbidden\",\"message\":\"REPORT module access required\",\"reason\":\""
                + decision.reason() + "\"}");
        return false;
    }

    private static Jwt authenticatedJwt() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        return authentication.getPrincipal() instanceof Jwt jwt ? jwt : null;
    }

    static String numericUserId(Jwt jwt) {
        for (String name : USER_ID_CLAIMS) {
            Object raw = jwt.getClaim(name);
            if (raw == null) {
                continue;
            }
            String value = raw instanceof Number n ? Long.toString(n.longValue()) : String.valueOf(raw).trim();
            if (!value.isEmpty() && value.chars().allMatch(Character::isDigit)) {
                return value;
            }
        }
        return null;
    }
}

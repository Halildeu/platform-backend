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
 * <p>The gate is asked with the caller's own bearer token, which
 * permission-service resolves to the canonical user itself — no claim is
 * trusted or guessed here.
 */
public class ReportModuleInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(ReportModuleInterceptor.class);

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
        ReportModuleAccessGate.Decision decision = gate.decide(jwt.getTokenValue());
        if (decision.allowed()) {
            return true;
        }
        log.info("schema.authz denied sub={} path={} reason={}", jwt.getSubject(), request.getRequestURI(),
                decision.reason());
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
}

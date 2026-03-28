package com.example.permission.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Basit bir iç servis kimlik doğrulaması sağlar. Beklenen anahtar
 * sağlanmazsa istek reddedilir.
 */
@Profile({"local", "dev"})
public class InternalApiKeyAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(InternalApiKeyAuthFilter.class);
    private static final String PRIMARY_HEADER = "X-Internal-Api-Key";
    private static final String LEGACY_HEADER = "X-Internal-API-Key";
    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/";

    private final String expectedApiKey;
    private final boolean legacyEnabled;

    public InternalApiKeyAuthFilter(String expectedApiKey, boolean legacyEnabled) {
        this.expectedApiKey = expectedApiKey;
        this.legacyEnabled = legacyEnabled;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        if (!legacyEnabled) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        if (path == null || !path.startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (SecurityContextHolder.getContext().getAuthentication() != null) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!StringUtils.hasText(expectedApiKey)) {
            log.error("Internal API key is not configured. Rejecting request to {}", path);
            reject(response);
            return;
        }

        String providedKey = resolveProvidedKey(request);
        if (!StringUtils.hasText(providedKey) || !expectedApiKey.equals(providedKey)) {
            log.warn("Internal API key validation failed for path {}", path);
            reject(response);
            return;
        }

        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken(
                        "internal-service",
                        null,
                        List.of(new SimpleGrantedAuthority("ROLE_INTERNAL")));
        authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        filterChain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response) throws IOException {
        SecurityContextHolder.clearContext();
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"Internal API key required\"}");
    }

    private String resolveProvidedKey(HttpServletRequest request) {
        String key = request.getHeader(PRIMARY_HEADER);
        if (StringUtils.hasText(key)) {
            return key;
        }
        return request.getHeader(LEGACY_HEADER);
    }
}

package com.example.ethics.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class PublicCredentialBoundaryFilter extends OncePerRequestFilter {
    public static final String MAILBOX_COOKIE = "__Host-etik_mailbox";
    private final ObjectMapper mapper;

    public PublicCredentialBoundaryFilter(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/public/ethics/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        boolean hasAuthorization = request.getHeader("Authorization") != null;
        boolean hasForeignCookie = false;
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (!MAILBOX_COOKIE.equals(cookie.getName()) && isSuiteCredential(cookie.getName())) {
                    hasForeignCookie = true;
                    break;
                }
            }
        }
        if (hasAuthorization || hasForeignCookie) {
            response.setStatus(400);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
                    "code", "CREDENTIAL_CONFUSION",
                    "message", "Bu public işlem için suite kimlik bilgisi kullanılamaz.")));
            return;
        }
        chain.doFilter(request, response);
    }

    private static boolean isSuiteCredential(String name) {
        String normalized=name==null?"":name.toUpperCase(java.util.Locale.ROOT);
        return Set.of("JSESSIONID","SESSION","KEYCLOAK_IDENTITY","KEYCLOAK_SESSION","AUTH_SESSION_ID").contains(normalized)
                || normalized.startsWith("KC_") || normalized.startsWith("SUITE_");
    }
}

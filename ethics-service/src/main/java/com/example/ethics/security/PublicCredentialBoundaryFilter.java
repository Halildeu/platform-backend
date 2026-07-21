package com.example.ethics.security;

import com.example.ethics.config.EthicsProperties;
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
    public static final String TRANSPORT_HEADER = "X-Etik-Speak-Transport";
    private final ObjectMapper mapper;
    private final EthicsProperties properties;

    public PublicCredentialBoundaryFilter(ObjectMapper mapper, EthicsProperties properties) {
        this.mapper = mapper;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/v1/public/ethics/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // Faz 35 ES-306 hardening — secure-transport gate ingress-nginx
        // `X-Etik-Speak-Transport: https` header'ını ya da standard
        // `X-Forwarded-Proto: https` (ingress-nginx `use-forwarded-headers`
        // altında otomatik set eder) header'ını kabul eder. Bu ikinci yol
        // ingress-nginx v1.9+ `proxy-set-headers` ConfigMap içindeki custom
        // header'ları render etmeyen (alfabetik-first-only) quirk için canonical
        // workaround; ayrıca sektör-standardı (nginx reverse-proxy defaults +
        // Spring Boot server.forward-headers-strategy=NATIVE) uyumludur.
        if (Boolean.TRUE.equals(properties.secureTransportRequired())
                && !isSecureTransport(request)) {
            writeBoundaryError(request, response, "SECURE_TRANSPORT_REQUIRED",
                    "Bu public işlem yalnız doğrulanmış HTTPS ingress üzerinden kullanılabilir.");
            return;
        }
        // Faz 35 ES-306 hardening — ingress-nginx `Authorization: ""` empty-set
        // rendering'i (basic-auth remove sonrası boş string) backend'e null
        // değil empty header olarak gelir. `getHeader() != null` check bu
        // durumu foreign-credential olarak yorumlar + CREDENTIAL_CONFUSION
        // reject. Fix: null + isBlank() birlikte kontrol (defense-in-depth).
        String authorization = request.getHeader("Authorization");
        boolean hasAuthorization = authorization != null && !authorization.isBlank();
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
            writeBoundaryError(request, response, "CREDENTIAL_CONFUSION",
                    "Bu public işlem için suite kimlik bilgisi kullanılamaz.");
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * Verifies the request arrived over HTTPS. Accepts either the Faz 35
     * dedicated transport proof header ({@code X-Etik-Speak-Transport: https})
     * or the standard {@code X-Forwarded-Proto: https} reverse-proxy header.
     * Both must be set by a trusted ingress; direct client control is
     * prevented by the boundary NetworkPolicy (application only accepts
     * traffic from the ingress-nginx namespace).
     */
    private boolean isSecureTransport(HttpServletRequest request) {
        String etikTransport = request.getHeader(TRANSPORT_HEADER);
        if ("https".equalsIgnoreCase(etikTransport)) {
            return true;
        }
        String forwardedProto = request.getHeader("X-Forwarded-Proto");
        return "https".equalsIgnoreCase(forwardedProto);
    }

    private void writeBoundaryError(
            HttpServletRequest request, HttpServletResponse response, String code, String message)
            throws IOException {
        response.setStatus(400);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object requestId = request.getAttribute(SensitiveResponseHeadersFilter.REQUEST_ID_ATTRIBUTE);
        mapper.writeValue(response.getOutputStream(), Map.of("error", Map.of(
                "code", code,
                "message", message,
                "requestId", requestId == null ? "unavailable" : requestId.toString())));
    }

    private static boolean isSuiteCredential(String name) {
        String normalized=name==null?"":name.toUpperCase(java.util.Locale.ROOT);
        return Set.of("JSESSIONID","SESSION","KEYCLOAK_IDENTITY","KEYCLOAK_SESSION","AUTH_SESSION_ID").contains(normalized)
                || normalized.startsWith("KC_") || normalized.startsWith("SUITE_");
    }
}

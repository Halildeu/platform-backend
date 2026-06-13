package com.example.endpointadmin.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Faz 22.5 Step-2 — bidirectional mTLS connector guard (Codex {@code 019ec0f9}
 * Slice 1b).
 *
 * <p>Registered as a top-level servlet filter at HIGHEST precedence and ONLY
 * when passthrough is enabled, so it runs <b>before</b> Spring Security and is
 * <b>independent of profile</b> — the {@code local}/{@code dev} permit-all
 * chain ({@code SecurityConfigLocal}) cannot bypass it. Two invariants:
 *
 * <ol>
 *   <li>{@code /api/v1/endpoint-agent/**} is reachable ONLY on the mTLS
 *       connector ({@code request.getLocalPort() == mtlsPort}). On any other
 *       connector → {@code 403 MTLS_CONNECTOR_REQUIRED}, short-circuited here so
 *       it never reaches the controller and never returns the business-path
 *       {@code MTLS_CERT_MISSING}.</li>
 *   <li>The mTLS connector serves ONLY {@code /api/v1/endpoint-agent/**}
 *       (least privilege — it is not a second HTTPS surface for the admin/JWT
 *       API). Any other path on that port → {@code 404}.</li>
 * </ol>
 *
 * <p>{@code getLocalPort()} is the backend connector that accepted the request —
 * not spoofable via {@code Host} / {@code X-Forwarded-*} / scheme (Codex
 * 019ec0f9 #2/#4). True network-level restriction additionally needs
 * Service/Ingress routing + NetworkPolicy (operator); this is the app-layer half.
 */
public class MtlsConnectorGuardFilter extends OncePerRequestFilter {

    public static final String ENDPOINT_AGENT_PREFIX = "/api/v1/endpoint-agent/";
    public static final String ERR_MTLS_REQUIRED = "MTLS_CONNECTOR_REQUIRED";

    private final int mtlsPort;

    public MtlsConnectorGuardFilter(int mtlsPort) {
        this.mtlsPort = mtlsPort;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();
        boolean isAgentPath = path != null && path.startsWith(ENDPOINT_AGENT_PREFIX);
        boolean onMtlsPort = request.getLocalPort() == mtlsPort;

        if (isAgentPath && !onMtlsPort) {
            // endpoint-agent traffic on the plain / management connector — refuse
            // before the business path so it can never surface MTLS_CERT_MISSING.
            writeError(response, HttpStatus.FORBIDDEN, ERR_MTLS_REQUIRED,
                    "endpoint-agent endpoints are served only on the mTLS connector");
            return;
        }
        if (onMtlsPort && !isAgentPath) {
            // least privilege: the mTLS connector is not a general HTTPS surface.
            writeError(response, HttpStatus.NOT_FOUND, "NOT_FOUND",
                    "the mTLS connector serves only endpoint-agent endpoints");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, HttpStatus status,
                            String error, String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"" + error + "\",\"message\":\"" + message + "\"}");
    }
}

package com.example.meeting.security;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;

/**
 * Faz 24 issue #818: emits a stable machine-readable error body for internal
 * ingestion path 401/403 responses without exposing secret/token material.
 *
 * <p>Contract (JSON object; UTF-8; media type {@code application/json}):
 * <ul>
 *   <li>{@code errorCode} — stable enum: {@code AUTH_TOKEN_MISSING},
 *       {@code AUTH_TOKEN_INVALID}, {@code AUTH_FORBIDDEN}.</li>
 *   <li>{@code message} — short constant hint, never carries the raw token or
 *       the offending claim value.</li>
 *   <li>{@code correlationId} — echoes {@code X-Correlation-Id} if present,
 *       else generates a fresh UUID so operators can grep logs.</li>
 *   <li>{@code retryable} — {@code false} for 401 (client must re-authenticate),
 *       {@code false} for 403 (authorization gap, not a transient failure).</li>
 * </ul>
 *
 * <p>The response body deliberately does not include the token, iss, aud, sub,
 * scope, permission, or any header/claim value. All operator troubleshooting
 * runs through {@code correlationId} + server logs.
 */
public final class StructuredAuthErrorEntryPoint
        implements AuthenticationEntryPoint, AccessDeniedHandler {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";
    private static final String CODE_TOKEN_MISSING = "AUTH_TOKEN_MISSING";
    private static final String CODE_TOKEN_INVALID = "AUTH_TOKEN_INVALID";
    private static final String CODE_FORBIDDEN = "AUTH_FORBIDDEN";
    private static final String MESSAGE_TOKEN_MISSING = "Authentication is required";
    private static final String MESSAGE_TOKEN_INVALID = "Authentication token is invalid or expired";
    private static final String MESSAGE_FORBIDDEN = "Not permitted to access the requested resource";

    private final ObjectMapper objectMapper;

    public StructuredAuthErrorEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        String errorCode = pickAuthErrorCode(request, authException);
        String message = errorCode.equals(CODE_TOKEN_MISSING) ? MESSAGE_TOKEN_MISSING : MESSAGE_TOKEN_INVALID;
        writeBody(request, response, HttpStatus.UNAUTHORIZED, errorCode, message);
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        writeBody(request, response, HttpStatus.FORBIDDEN, CODE_FORBIDDEN, MESSAGE_FORBIDDEN);
    }

    private void writeBody(
            HttpServletRequest request,
            HttpServletResponse response,
            HttpStatus status,
            String errorCode,
            String message)
            throws IOException {
        String correlationId = pickCorrelationId(request);
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        // Echo the correlation-id so it is visible to callers that only read
        // the headers (some clients strip response bodies on non-2xx).
        response.setHeader(CORRELATION_HEADER, correlationId);
        // Explicit no-store keeps intermediary caches from persisting the error
        // shape as if it were the resource; only relevant if a proxy sees 401.
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        try (JsonGenerator generator = objectMapper.createGenerator(response.getOutputStream())) {
            generator.writeStartObject();
            generator.writeStringField("errorCode", errorCode);
            generator.writeStringField("message", message);
            generator.writeStringField("correlationId", correlationId);
            generator.writeBooleanField("retryable", false);
            generator.writeEndObject();
        }
    }

    private static String pickCorrelationId(HttpServletRequest request) {
        String header = request.getHeader(CORRELATION_HEADER);
        if (header != null && !header.isBlank() && header.length() <= 128) {
            // Restrict to opaque printable ASCII so a hostile client cannot
            // inject control characters into the response envelope.
            for (int i = 0; i < header.length(); i++) {
                char c = header.charAt(i);
                if (c < 0x20 || c > 0x7e) {
                    return UUID.randomUUID().toString();
                }
            }
            return header;
        }
        return UUID.randomUUID().toString();
    }

    private static String pickAuthErrorCode(HttpServletRequest request, AuthenticationException exception) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization == null || authorization.isBlank()) {
            return CODE_TOKEN_MISSING;
        }
        // Any AuthenticationException path with a supplied Authorization header
        // is treated as an invalid/expired token — expired vs invalid vs
        // missing-scope is deliberately not leaked to the caller.
        return CODE_TOKEN_INVALID;
    }
}

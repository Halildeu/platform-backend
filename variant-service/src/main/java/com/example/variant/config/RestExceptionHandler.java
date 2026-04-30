package com.example.variant.config;

import com.example.variant.authz.AuthzDependencyUnavailableException;
import com.example.variant.authz.AuthzIdentityResolutionException;
import com.example.variant.authz.AuthzUpstreamInvalidResponseException;
import com.example.variant.theme.service.ThemeNotFoundException;
import com.example.variant.theme.service.ThemeValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ThemeValidationException.class)
    public ResponseEntity<Map<String, Object>> handleThemeValidationException(ThemeValidationException ex) {
        log.warn("Theme validation error: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(buildBody("THEME_VALIDATION_ERROR", ex.getMessage()));
    }

    @ExceptionHandler(ThemeNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleThemeNotFoundException(ThemeNotFoundException ex) {
        log.warn("Theme not found: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(buildBody("THEME_NOT_FOUND", ex.getMessage()));
    }

    // Codex 019dddb7 iter-42 — authz upstream classification.
    // Pre-iter-42 every authz failure path collapsed to HTTP 401 because
    // the variant controllers inspected an empty AuthzMeResponse and
    // assumed "no identity = unauthenticated". The frontend's shared-http
    // listener turned every 401 into a global session expiry, so any
    // permission-service blip would log users out. We now classify the
    // failure mode so the frontend can render a transient-error toast
    // instead of breaking the session.

    @ExceptionHandler(AuthzDependencyUnavailableException.class)
    public ResponseEntity<Map<String, Object>> handleAuthzDependencyUnavailable(
            AuthzDependencyUnavailableException ex) {
        log.warn("Authz dependency unavailable: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(buildBody("AUTHZ_DEPENDENCY_UNAVAILABLE", ex.getMessage()));
    }

    @ExceptionHandler(AuthzUpstreamInvalidResponseException.class)
    public ResponseEntity<Map<String, Object>> handleAuthzUpstreamInvalidResponse(
            AuthzUpstreamInvalidResponseException ex) {
        log.warn("Authz upstream returned invalid response: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(buildBody("AUTHZ_UPSTREAM_INVALID", ex.getMessage()));
    }

    @ExceptionHandler(AuthzIdentityResolutionException.class)
    public ResponseEntity<Map<String, Object>> handleAuthzIdentityResolution(
            AuthzIdentityResolutionException ex) {
        log.warn("Authz identity resolution failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(buildBody("AUTHZ_IDENTITY_RESOLUTION", ex.getMessage()));
    }

    private Map<String, Object> buildBody(String errorCode, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("errorCode", errorCode);
        body.put("message", message);
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            body.put("traceId", traceId);
        }
        return body;
    }
}


package com.example.permission.controller;

import com.example.permission.dataaccess.AccessScopeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Faz 21.3 PR-D: maps {@link AccessScopeException} subclasses onto stable HTTP
 * codes for {@link AccessScopeController} only. Scoped via
 * {@code assignableTypes} so we do not interfere with the global
 * {@code GlobalExceptionHandler} or any other controller-advice in the module.
 */
@RestControllerAdvice(assignableTypes = AccessScopeController.class)
public class AccessScopeExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(AccessScopeExceptionHandler.class);

    @ExceptionHandler(AccessScopeException.ScopeNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(
            AccessScopeException.ScopeNotFoundException ex) {
        log.debug("scope not found: {}", ex.getScopeId());
        Map<String, Object> body = baseBody(ex);
        body.put("scope_id", ex.getScopeId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(AccessScopeException.ScopeAlreadyRevokedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyRevoked(
            AccessScopeException.ScopeAlreadyRevokedException ex) {
        log.debug("scope already revoked: id={} at={}", ex.getScopeId(), ex.getRevokedAt());
        Map<String, Object> body = baseBody(ex);
        body.put("scope_id", ex.getScopeId());
        body.put("revoked_at", ex.getRevokedAt());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    @ExceptionHandler(AccessScopeException.ScopeAlreadyGrantedException.class)
    public ResponseEntity<Map<String, Object>> handleAlreadyGranted(
            AccessScopeException.ScopeAlreadyGrantedException ex) {
        log.debug("scope already granted (active duplicate): {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(baseBody(ex));
    }

    @ExceptionHandler(AccessScopeException.ScopeValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(
            AccessScopeException.ScopeValidationException ex) {
        log.warn("scope validation failed: {}", ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(baseBody(ex));
    }

    private static Map<String, Object> baseBody(AccessScopeException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", ex.getErrorCode());
        body.put("message", ex.getMessage());
        return body;
    }
}

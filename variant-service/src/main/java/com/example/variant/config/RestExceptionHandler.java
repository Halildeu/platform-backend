package com.example.variant.config;

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


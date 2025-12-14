package com.example.variant.theme.controller;

import com.example.variant.theme.service.ThemeNotFoundException;
import com.example.variant.theme.service.ThemeValidationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.Map;

@RestControllerAdvice
public class ThemeExceptionHandler {

    @ExceptionHandler(ThemeNotFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNotFound(ThemeNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorBody("THEME_NOT_FOUND", ex.getMessage()));
    }

    @ExceptionHandler(ThemeValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ThemeValidationException ex) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(errorBody("THEME_VALIDATION_ERROR", ex.getMessage()));
    }

    private Map<String, Object> errorBody(String code, String message) {
        return Map.of(
            "timestamp", Instant.now().toString(),
            "code", code,
            "message", message
        );
    }
}


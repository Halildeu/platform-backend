package com.example.user.config;

import com.example.user.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.server.ResponseStatusException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ApiErrorResponse> handleResponseStatusException(ResponseStatusException ex) {
        log.warn("ResponseStatusException yakalandı: {}", ex.getReason(), ex);
        String errorCode = ex.getReason() != null ? ex.getReason() : ex.getStatusCode().toString();
        String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
        ApiErrorResponse body = ApiErrorResponse.of(errorCode, message, MDC.get("traceId"));
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleMethodArgumentNotValidException(MethodArgumentNotValidException ex) {
        log.warn("Validation hatası: {}", ex.getMessage());
        Map<String, Object> meta = new HashMap<>(ApiErrorResponse.of("ERR_VALIDATION", "Validation failed", MDC.get("traceId")).meta());
        List<Map<String, String>> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(err -> Map.of(
                        "field", err.getField(),
                        "message", err.getDefaultMessage() == null ? "invalid" : err.getDefaultMessage()))
                .toList();
        meta.put("fieldErrors", fieldErrors);
        ApiErrorResponse body = new ApiErrorResponse("ERR_VALIDATION", "Validation failed", meta);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleHttpMessageNotReadableException(HttpMessageNotReadableException ex) {
        log.warn("Geçersiz JSON/gövde: {}", ex.getMessage());
        ApiErrorResponse body = ApiErrorResponse.of("ERR_BAD_REQUEST", "Invalid request body", MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleGenericException(Exception ex) {
        log.error("Beklenmeyen hata yakalandı", ex);
        ApiErrorResponse body = ApiErrorResponse.of(
                "INTERNAL_SERVER_ERROR",
                "Beklenmeyen bir hata oluştu",
                MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}

package com.example.meeting.exception;

import com.example.meeting.dto.ErrorResponse;
import com.example.meeting.dto.ErrorResponse.FieldError;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Central error mapper. Adapted from endpoint-admin-service
 * {@code GlobalExceptionHandler}, trimmed to the handlers a CRUD service
 * needs (no install-blocked / policy-approval domain exceptions). Maps
 * validation / parse / type-mismatch / missing-param failures to 400,
 * optimistic-lock races to 409, and falls back to 500 for anything
 * unhandled — every body is the canonical {@link ErrorResponse}.
 */
@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String code = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        String message = ex.getReason() != null ? ex.getReason() : "Unexpected error.";
        return build(status, code, message);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        BindingResult bindingResult = ex.getBindingResult();
        List<FieldError> fieldErrors = bindingResult.getFieldErrors()
                .stream()
                .map(error -> new FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", fieldErrors);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Resource not found.");
    }

    /**
     * {@code @RequestParam} / {@code @PathVariable} type or enum
     * conversion failures (e.g. {@code ?status=BOGUS}, bad UUID path
     * variable) → 400 with the named parameter, not the catch-all 500.
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "?";
        String message = "Invalid value for parameter '" + paramName
                + "' (expected " + requiredType + ").";
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message);
    }

    /**
     * Malformed JSON / unknown enum value inside {@code @RequestBody} →
     * 400, not 500.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleBodyParseError(
            HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST_BODY",
                "Request body could not be parsed.");
    }

    /**
     * Missing required {@code @RequestParam} → 400, not 500.
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParam(
            MissingServletRequestParameterException ex) {
        String paramName = ex.getParameterName();
        String paramType = ex.getParameterType();
        String message = "Missing required parameter '" + paramName
                + "' (" + paramType + ").";
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", message);
    }

    /**
     * Concurrent writers race on the same row (JPA {@code @Version}
     * mismatch) → 409 so the UI can refresh and retry.
     */
    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ErrorResponse> handleOptimisticLock(
            OptimisticLockingFailureException ex) {
        log.warn("optimistic lock race: {}", ex.getMessage());
        return build(HttpStatus.CONFLICT, "concurrent_modification",
                "This record was modified concurrently. Refresh and retry.");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected error.");
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message) {
        return build(status, error, message, ErrorResponse.emptyFieldErrors());
    }

    private ResponseEntity<ErrorResponse> build(
            HttpStatus status,
            String error,
            String message,
            List<FieldError> fieldErrors
    ) {
        String traceId = resolveTraceId();
        ErrorResponse response = ErrorResponse.of(error, message, fieldErrors, traceId);
        log.warn("error={} status={} traceId={} message={}", error, status.value(), traceId, message);
        return ResponseEntity.status(status).body(response);
    }

    private String resolveTraceId() {
        String existing = MDC.get("traceId");
        if (existing != null && !existing.isBlank()) {
            return existing;
        }
        String generated = UUID.randomUUID().toString();
        MDC.put("traceId", generated);
        return generated;
    }
}

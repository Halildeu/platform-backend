package com.example.permission.exception;

import com.example.permission.dto.ErrorResponse;
import com.example.permission.dto.ErrorResponse.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", ex.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
        return build(HttpStatus.FORBIDDEN, "FORBIDDEN", ex.getMessage() != null ? ex.getMessage() : "Bu işlem için yetkiniz bulunmuyor.");
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    public ResponseEntity<ErrorResponse> handleBadRequest(RuntimeException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", ex.getMessage());
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatus status = HttpStatus.valueOf(ex.getStatusCode().value());
        String code = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();
        String message = ex.getReason() != null ? ex.getReason() : "Beklenmeyen bir hata oluştu.";
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
    // Slice E (#2555) — Spring 6.1+ splits validation cascade errors on
    // @Valid @RequestParam / @PathVariable into HandlerMethodValidationException
    // instead of MethodArgumentNotValidException. Without an explicit
    // handler these fall through Exception.class → 500 even though they
    // are client-side input violations. Sektör standardı: 400 + fieldErrors.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        List<FieldError> fieldErrors = ex.getAllValidationResults().stream()
                .flatMap(result -> {
                    String rawField = result.getMethodParameter().getParameterName();
                    if (rawField == null) rawField = "arg" + result.getMethodParameter().getParameterIndex();
                    final String field = rawField;
                    return result.getResolvableErrors().stream()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .map(msg -> new FieldError(field, msg == null ? "invalid" : msg));
                })
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", fieldErrors);
    }


    // #2555 Slice C — Spring @RequestParam bind failures must surface as 400,
    // not the generic Exception.class catch-all 500. Same pattern already used
    // in transcript-service / endpoint-admin-service GlobalExceptionHandler.
    // Reported via GET /api/v1/access/scope: invalid UUID / Long or missing
    // required param produced INTERNAL_ERROR 500 in prior behavior; each is
    // a user-input error and belongs at 4xx.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleArgumentTypeMismatch(
            MethodArgumentTypeMismatchException ex) {
        String paramName = ex.getName();
        String requiredType = ex.getRequiredType() != null
                ? ex.getRequiredType().getSimpleName()
                : "?";
        String message = "Invalid value for parameter '" + paramName
                + "' (expected " + requiredType + ").";
        List<FieldError> fieldErrors = List.of(new FieldError(paramName, message));
        return build(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", message, fieldErrors);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingRequestParam(
            MissingServletRequestParameterException ex) {
        String paramName = ex.getParameterName();
        String paramType = ex.getParameterType();
        String message = "Missing required parameter '" + paramName
                + "' (" + paramType + ").";
        List<FieldError> fieldErrors = List.of(new FieldError(paramName, message));
        return build(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", message, fieldErrors);
    }

    // Slice D-parity (#2555) — sister services already map malformed
    // JSON body to 400; permission-service was missing the handler and
    // would surface truncated/invalid POST bodies as generic 500 via the
    // catch-all. Client-side error → 400.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST",
                "İstek gövdesi okunamadı (geçersiz JSON).");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception", ex);
        // B2 (Rev 19): /authz/me errors must return 503 (not 200+empty body, not 500).
        // Variant-service caches empty AuthzMeResponse for 5 minutes -> sticky 403.
        // 503 signals degraded state; clients should retry instead of caching empty data.
        String path = request != null ? request.getRequestURI() : "";
        if (path.endsWith("/api/v1/authz/me") || path.endsWith("/v1/authz/me")) {
            log.warn("B2: /authz/me hata; 503 dönülüyor (path={})", path);
            return build(HttpStatus.SERVICE_UNAVAILABLE, "AUTHZ_DEGRADED", "Yetki servisi geçici olarak kullanılamıyor.");
        }
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Beklenmeyen bir hata oluştu.");
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message) {
        return build(status, error, message, ErrorResponse.emptyFieldErrors());
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String error, String message, List<FieldError> fieldErrors) {
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

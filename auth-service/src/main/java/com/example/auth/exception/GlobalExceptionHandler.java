package com.example.auth.exception;

import com.example.auth.dto.ErrorResponse;
import com.example.auth.user.UserAlreadyExistsException;
import com.example.auth.dto.ErrorResponse.FieldError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.BindingResult;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.List;
import java.util.UUID;

@ControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ErrorResponse> handleAuth(AuthenticationException ex) {
        return build(HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", ex.getMessage());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    public ResponseEntity<ErrorResponse> handleUserExists(UserAlreadyExistsException ex) {
        return build(HttpStatus.CONFLICT, "USER_ALREADY_EXISTS", ex.getMessage());
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

    // Spring Boot 3.2+ — bilinmeyen path / static resource bulunamazsa.
    // Codex Tur-3 #5: main port 8088'de /actuator/* (management ayrı port'ta) erişen
    // istek bu exception'a düşüyordu, handleGeneric 500 dönüyordu → gateway ve client
    // için kontrollü 404.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException ex) {
        return build(HttpStatus.NOT_FOUND, "NOT_FOUND", "Kaynak bulunamadı.");
    }

    /**
     * Session 47 stabilization regression — {@code GET /api/v1/impersonation/sessions?status=ACTIVE}
     * (no {@code @GetMapping} on the root path) was falling through
     * {@link #handleGeneric(Exception)} and returning {@code 500
     * INTERNAL_ERROR} instead of {@code 405 Method Not Allowed}, which
     * leaks Spring routing details to API clients and confuses FE error
     * mapping. The canonical lookup endpoint for the user's active
     * session is {@code GET /api/v1/impersonation/sessions/active}.
     *
     * <p>Maps {@link HttpRequestMethodNotSupportedException} to a
     * controlled 405 with {@code METHOD_NOT_ALLOWED} error code so the
     * FE error map and audit traces capture the real cause. Codex
     * {@code 019e1dd6} REVISE-1 absorb: includes the {@code Allow}
     * response header listing supported HTTP methods (RFC 7231 §6.5.5
     * MUST-requirement for 405 responses) so HTTP-aware clients can
     * negotiate without parsing the body.
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex) {
        ResponseEntity<ErrorResponse> response = build(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED",
                "Bu endpoint için '" + ex.getMethod() + "' metodu desteklenmiyor.");
        String[] supported = ex.getSupportedMethods();
        if (supported != null && supported.length > 0) {
            return ResponseEntity.status(response.getStatusCode())
                    .header(org.springframework.http.HttpHeaders.ALLOW, String.join(", ", supported))
                    .body(response.getBody());
        }
        return response;
    }

    // #2555 Slice-D-parity — malformed JSON body (e.g. POST /auth/login
    // with truncated payload) previously fell through Exception.class →
    // 500. Client-side error → 400.
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex) {
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "İstek gövdesi okunamadı (geçersiz JSON).");
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
                    String field = result.getMethodParameter().getParameterName();
                    if (field == null) field = "arg" + result.getMethodParameter().getParameterIndex();
                    final String fieldName = field;
                    return result.getResolvableErrors().stream()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .map(msg -> new FieldError(fieldName, msg == null ? "invalid" : msg));
                })
                .toList();
        return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", fieldErrors);
    }

    // #2555 Slice-D-parity — path variable or query param type mismatch.
    // Instead of a generic 500, return 400 with the offending field name
    // and expected type so callers can correct their request.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        String expected = ex.getRequiredType() == null ? "unknown" : ex.getRequiredType().getSimpleName();
        FieldError fe = new FieldError(ex.getName(),
                "Geçersiz tip: " + ex.getValue() + " (beklenen: " + expected + ")");
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Geçersiz parametre tipi.", List.of(fe));
    }

    // #2555 Slice-D-parity — required query/header parameter missing.
    // 400 + fieldErrors[].field naming the missing parameter.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        FieldError fe = new FieldError(ex.getParameterName(),
                "Zorunlu parametre eksik (tip: " + ex.getParameterType() + ")");
        return build(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Zorunlu parametre eksik.", List.of(fe));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex) {
        log.error("Unhandled exception", ex);
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

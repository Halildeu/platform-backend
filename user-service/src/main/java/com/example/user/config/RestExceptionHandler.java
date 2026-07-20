package com.example.user.config;

import com.example.user.dto.ApiErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

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

    // Slice E (#2555) — Spring 6.1+ @Valid @RequestParam / @PathVariable
    // cascade validation → HandlerMethodValidationException, not
    // MethodArgumentNotValidException. Without this branch these fall
    // through Exception.class → 500. Client-side violation → 400.
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleHandlerMethodValidation(HandlerMethodValidationException ex) {
        log.info("cascade validation failed: {}", ex.getMessage());
        Map<String, Object> meta = new HashMap<>(ApiErrorResponse.of(
                "ERR_VALIDATION", "Validation failed", MDC.get("traceId")).meta());
        List<Map<String, String>> fieldErrors = ex.getAllValidationResults().stream()
                .flatMap(result -> {
                    String rawField = result.getMethodParameter().getParameterName();
                    if (rawField == null) rawField = "arg" + result.getMethodParameter().getParameterIndex();
                    final String field = rawField;
                    return result.getResolvableErrors().stream()
                            .map(MessageSourceResolvable::getDefaultMessage)
                            .map(msg -> Map.of(
                                    "field", field,
                                    "message", msg == null ? "invalid" : msg));
                })
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

    // Slice D (#2721) — unmapped path 500→404. Spring 6+ artık Whitelabel yerine
    // NoResourceFoundException fırlatıyor; hiç handle etmezsek Exception.class
    // catch-all'a düşüyor ve kullanıcı generic 500 görüyor. Yeni giriş yapan
    // kullanıcı yanlış URL'ye tıkladığında "hesabınız yok mu?" belirsizliğine
    // düşüyordu — açıkça 404.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiErrorResponse> handleNoResourceFound(NoResourceFoundException ex) {
        log.info("Unmapped path: {}", ex.getResourcePath());
        ApiErrorResponse body = ApiErrorResponse.of(
                "NOT_FOUND",
                "Kaynak bulunamadı: " + ex.getResourcePath(),
                MDC.get("traceId"));
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    // Slice D — path variable veya query param type mismatch. Örn.
    // GET /api/v1/users/abc (Long parse fail) → şu an 500. Kullanıcının
    // düzeltmesi mümkün bir input hatası → 400 + hangi alanın yanlış olduğu.
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.info("Type mismatch: name={} value={} required={}",
                ex.getName(), ex.getValue(),
                ex.getRequiredType() == null ? null : ex.getRequiredType().getSimpleName());
        Map<String, Object> meta = new HashMap<>(ApiErrorResponse.of(
                "ERR_BAD_REQUEST", "Geçersiz parametre tipi", MDC.get("traceId")).meta());
        Map<String, Object> fieldError = new HashMap<>();
        fieldError.put("field", ex.getName());
        fieldError.put("rejectedValue", ex.getValue());
        fieldError.put("expectedType",
                ex.getRequiredType() == null ? null : ex.getRequiredType().getSimpleName());
        meta.put("fieldErrors", List.of(fieldError));
        ApiErrorResponse body = new ApiErrorResponse(
                "ERR_BAD_REQUEST", "Geçersiz parametre tipi", meta);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // Slice D — required query/header param eksik. Örn.
    // GET /api/v1/users/search (query eksik) → şu an 500. 400 + eksik alan adı.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.info("Missing parameter: name={} type={}", ex.getParameterName(), ex.getParameterType());
        Map<String, Object> meta = new HashMap<>(ApiErrorResponse.of(
                "ERR_BAD_REQUEST", "Zorunlu parametre eksik", MDC.get("traceId")).meta());
        Map<String, Object> fieldError = new HashMap<>();
        fieldError.put("field", ex.getParameterName());
        fieldError.put("expectedType", ex.getParameterType());
        fieldError.put("message", "required");
        meta.put("fieldErrors", List.of(fieldError));
        ApiErrorResponse body = new ApiErrorResponse(
                "ERR_BAD_REQUEST", "Zorunlu parametre eksik", meta);
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

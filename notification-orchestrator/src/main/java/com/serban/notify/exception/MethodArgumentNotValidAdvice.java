package com.serban.notify.exception;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Global advice that turns Spring's bean-validation failures on
 * {@code @Valid @RequestBody} DTOs into a structured 400 response with
 * field-level error detail and a WARN log line, so future canary smoke
 * runs can diagnose payload issues without having to enable
 * {@code server.error.include-message=always} (which exposes Spring's
 * internal exception messages on every other 4xx path too).
 *
 * <h4>Scope</h4>
 * <ul>
 *   <li>{@link MethodArgumentNotValidException} — fired when an
 *       {@code @Valid @RequestBody} DTO violates a constraint (e.g.
 *       {@code @NotBlank}, {@code @Size}, {@code @Pattern}).</li>
 *   <li>{@link ConstraintViolationException} — fired when a
 *       {@code @Validated @PathVariable} / {@code @RequestParam}
 *       violates a constraint. Most controllers historically handled
 *       this per-controller (InboxController, PreferenceController,
 *       InboxSseController); this advice provides the same shape so
 *       new controllers do not need to re-implement the boilerplate.
 *       Per-controller handlers continue to take precedence — Spring's
 *       resolver picks the closest match first.</li>
 * </ul>
 *
 * <h4>Why not server.error.include-message=always</h4>
 * That flag exposes raw exception messages on every 4xx/5xx, including
 * Jackson type-mismatch traces and Spring internal exception text.
 * This advice surfaces ONLY the developer-authored validation messages
 * declared on the DTO constraints, keyed by field name, so the payload
 * shape stays predictable and the security boundary stays narrow.
 *
 * <h4>Response shape</h4>
 * <pre>
 * {
 *   "error": "validation",
 *   "message": "request body failed validation; see details",
 *   "details": [
 *     { "field": "intentId", "message": "intent_id required" },
 *     { "field": "orgId",    "message": "org_id required" }
 *   ]
 * }
 * </pre>
 *
 * Tracked-by: platform-backend#304, BL-010 follow-up. Codex strategic
 * precursor thread: {@code 019e5a75} (BL-010 KC org_id mapper).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class MethodArgumentNotValidAdvice {

    private static final Logger log = LoggerFactory.getLogger(MethodArgumentNotValidAdvice.class);

    /**
     * Convert a {@link MethodArgumentNotValidException} into a 400 with
     * a {@code details[]} array carrying one entry per offending field.
     *
     * <p>The WARN log line includes the controller path + the offending
     * field names but NOT the rejected values, so a leaky payload
     * (e.g. an org_id that looks like a secret) cannot leak through
     * the log aggregator. Caller IP + the structured details give the
     * canary-smoke operator enough to diagnose without the full body.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        var details = new java.util.ArrayList<Map<String, String>>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String msg = fe.getDefaultMessage();
            details.add(Map.of(
                    "field", fe.getField(),
                    "message", msg != null ? msg : "invalid"));
        }
        // Also surface non-field (global / class-level) errors so a
        // @ScriptAssert-style cross-field rule does not vanish.
        ex.getBindingResult().getGlobalErrors().forEach(ge -> {
            String msg = ge.getDefaultMessage();
            details.add(Map.of(
                    "field", ge.getObjectName(),
                    "message", msg != null ? msg : "invalid"));
        });

        // Log field names only — never the rejected values (avoid
        // payload leak through the log aggregator).
        String fields = details.stream()
                .map(d -> d.get("field"))
                .reduce((a, b) -> a + "," + b)
                .orElse("(none)");
        log.warn("validation failed: target={} fields=[{}] errorCount={}",
                ex.getParameter().getMethod() != null
                        ? ex.getParameter().getMethod().getName()
                        : "(unknown)",
                fields,
                details.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation");
        body.put("message", "request body failed validation; see details");
        body.put("details", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    /**
     * Same shape for {@link ConstraintViolationException} (path /
     * query-parameter constraint failures on {@code @Validated}
     * controllers). Per-controller handlers continue to take precedence
     * because Spring resolves to the most specific handler first; this
     * is the fallback for controllers that do not yet provide their own.
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> handleConstraintViolation(
            ConstraintViolationException ex) {
        var details = ex.getConstraintViolations().stream()
                .map(cv -> Map.of(
                        "field", cv.getPropertyPath().toString(),
                        "message", cv.getMessage()))
                .toList();

        String fields = details.stream()
                .map(d -> d.get("field"))
                .reduce((a, b) -> a + "," + b)
                .orElse("(none)");
        log.warn("validation failed (path/query): fields=[{}] errorCount={}",
                fields, details.size());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", "validation");
        body.put("message", "request path or query parameters failed validation; see details");
        body.put("details", details);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}

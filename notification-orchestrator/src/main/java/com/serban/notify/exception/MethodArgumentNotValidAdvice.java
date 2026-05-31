package com.serban.notify.exception;

import java.util.ArrayList;
import java.util.List;
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
 * {@code @Valid @RequestBody} DTOs into a {@link ValidationErrorResponse}
 * 400 with field-level detail and a WARN log line, so future canary
 * smoke runs can diagnose payload issues without having to enable
 * {@code server.error.include-message=always} (which exposes Spring's
 * internal exception messages on every other 4xx path too).
 *
 * <h4>Scope (narrowed per Codex 019e806a iter-1 PARTIAL #2)</h4>
 * This advice handles ONLY {@link MethodArgumentNotValidException}
 * — the case raised on {@code @Valid @RequestBody}. The companion
 * {@code ConstraintViolationException} (path / query-parameter
 * constraint failures on {@code @Validated} controllers) is
 * deliberately NOT handled here:
 * <ul>
 *   <li>InboxController, PreferenceController, InboxSseController
 *       already ship per-controller {@code @ExceptionHandler(
 *       ConstraintViolationException.class)} handlers with a different
 *       body shape ({@code {error, message}}). A global advice carrying
 *       {@code details[]} would be silently overridden by Spring's
 *       resolver (local handler always wins, regardless of advice
 *       {@code @Order}). Documenting two different body shapes for the
 *       same 400 class is a contract drift the OpenAPI cannot capture
 *       cleanly.</li>
 *   <li>If we want a uniform {@link ValidationErrorResponse} shape on
 *       the path / query side too, the right move is a separate PR
 *       that retires the per-controller handlers and migrates them to
 *       this advice (one body shape, one OpenAPI schema) — out of
 *       scope here.</li>
 * </ul>
 *
 * <h4>Why not server.error.include-message=always</h4>
 * That flag exposes raw exception messages on every 4xx/5xx, including
 * Jackson type-mismatch traces and Spring internal exception text.
 * This advice surfaces ONLY the developer-authored validation messages
 * declared on the DTO constraints, keyed by field name, so the payload
 * shape stays predictable and the security boundary stays narrow.
 *
 * <h4>OpenAPI binding</h4>
 * The 400 response shape is declared as {@link ValidationErrorResponse}
 * — every {@code @ApiResponse(responseCode = "400")} that wants to
 * advertise this shape should add
 * {@code content = @Content(schema = @Schema(implementation = ValidationErrorResponse.class))}.
 *
 * Tracked-by: platform-backend#304, BL-010 follow-up.
 * Codex strategic precursor thread: {@code 019e5a75} (BL-010 KC org_id
 * mapper); post-impl review thread: {@code 019e806a}.
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
public class MethodArgumentNotValidAdvice {

    private static final Logger log = LoggerFactory.getLogger(MethodArgumentNotValidAdvice.class);

    /**
     * Cap the WARN log field list to keep noise bounded on a
     * high-volume validation 400 (Codex 019e806a hardening): even if a
     * future DTO trips dozens of fields at once, the log line stays a
     * single bounded message. The full count is still surfaced via
     * {@code errorCount=N}.
     */
    private static final int LOG_FIELD_NAME_CAP = 10;

    /**
     * Convert a {@link MethodArgumentNotValidException} into a 400 with
     * a {@link ValidationErrorResponse} body carrying one entry per
     * offending field plus any class-level / cross-field rules
     * ({@code getGlobalErrors()}).
     *
     * <p>The WARN log line carries the target method name and the
     * (capped) offending field names but NEVER the rejected values, so
     * a leaky payload (e.g. an org_id that looks like a secret) cannot
     * leak through the log aggregator.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ValidationErrorResponse> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex) {
        List<ValidationErrorDetail> details = new ArrayList<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            String msg = fe.getDefaultMessage();
            details.add(new ValidationErrorDetail(fe.getField(), msg != null ? msg : "invalid"));
        }
        // Surface non-field (global / class-level) errors so a
        // @ScriptAssert-style cross-field rule does not vanish; key is
        // the bean's objectName so the client can distinguish a
        // per-field error from a global one.
        ex.getBindingResult().getGlobalErrors().forEach(ge -> {
            String msg = ge.getDefaultMessage();
            details.add(new ValidationErrorDetail(ge.getObjectName(), msg != null ? msg : "invalid"));
        });

        // Log field names only — never the rejected values (avoid
        // payload leak through the log aggregator). Cap the field list
        // so a malformed payload tripping dozens of fields does not
        // blow up the log line.
        String fields = details.stream()
                .map(ValidationErrorDetail::field)
                .limit(LOG_FIELD_NAME_CAP)
                .reduce((a, b) -> a + "," + b)
                .orElse("(none)");
        String overflowSuffix = details.size() > LOG_FIELD_NAME_CAP ? ",…" : "";
        log.warn("validation failed: target={} fields=[{}{}] errorCount={}",
                ex.getParameter().getMethod() != null
                        ? ex.getParameter().getMethod().getName()
                        : "(unknown)",
                fields,
                overflowSuffix,
                details.size());

        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(new ValidationErrorResponse(
                        "validation",
                        "request body failed validation; see details",
                        details));
    }
}

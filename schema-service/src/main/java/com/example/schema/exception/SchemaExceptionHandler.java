package com.example.schema.exception;

import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Q3 (handoff section 5 - Codex 019e335c): global web-layer translation of
 * schema-service domain exceptions.
 *
 * <p>{@link SnapshotUnavailableException} means base table extraction from
 * the source database failed, so no snapshot could be built. Every
 * {@code SchemaController} endpoint that calls {@code buildSnapshot} (10 of
 * them) used to surface this as a generic HTTP 500; this advice answers a
 * controlled HTTP 503 (Service Unavailable) instead.
 *
 * <p>The response body is deliberately SANITIZED: the exception cause may
 * carry SQL text, the JDBC URL or other source-database internals, so only a
 * fixed, generic {@code reason} string plus the (non-sensitive) schema name
 * are returned. The full cause is logged server-side only.
 */
@RestControllerAdvice
public class SchemaExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(SchemaExceptionHandler.class);

    /** Fixed, leak-free reason — never the raw cause message. */
    private static final String SNAPSHOT_UNAVAILABLE_REASON =
            "Schema snapshot could not be built: base table extraction from the "
            + "source database failed.";

    @ExceptionHandler(SnapshotUnavailableException.class)
    public ResponseEntity<Map<String, String>> handleSnapshotUnavailable(
            SnapshotUnavailableException ex) {
        // Cause logged server-side only (may carry SQL / JDBC internals).
        log.warn("Snapshot unavailable for schema '{}'", ex.schema(), ex);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "snapshot_unavailable",
                        "schema", ex.schema(),
                        "reason", SNAPSHOT_UNAVAILABLE_REASON));
    }
}

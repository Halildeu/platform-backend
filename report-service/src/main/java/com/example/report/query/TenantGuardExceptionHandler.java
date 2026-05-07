package com.example.report.query;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Phase 2 Program 2a — Maps tenant-guard typed exceptions to deterministic
 * HTTP error responses.
 *
 * <p>Codex iter-10 §2a-AGREE absorb (thread 019e0119):
 * <ul>
 *   <li>{@link TenantSelectionRequiredException} → 400 {@code tenant_selection_required}</li>
 *   <li>{@link SchemaResolverMissException} → 503 {@code schema_resolver_miss}</li>
 * </ul>
 *
 * <p>Web-layer mapping kept separate from query layer so resolver stays
 * agnostic of HTTP semantics.
 */
@RestControllerAdvice
public class TenantGuardExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantGuardExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public TenantGuardExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(TenantSelectionRequiredException.class)
    public ResponseEntity<JsonNode> handleTenantSelectionRequired(TenantSelectionRequiredException e) {
        log.info("tenant_selection_required: report={} message={}", e.reportKey(), e.getMessage());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("error", "tenant_selection_required");
        body.put("reportKey", e.reportKey());
        body.put("message", e.getMessage());
        body.put("hint", "Provide an X-Company-Id header (super-admin) or ensure JWT contains a COMPANY scope.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(SchemaResolverMissException.class)
    public ResponseEntity<JsonNode> handleSchemaResolverMiss(SchemaResolverMissException e) {
        log.warn("schema_resolver_miss: report={} attempted={} message={}",
                e.reportKey(), e.attemptedSchemas(), e.getMessage());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("error", "schema_resolver_miss");
        body.put("reportKey", e.reportKey());
        body.set("attemptedSchemas", objectMapper.valueToTree(e.attemptedSchemas()));
        body.put("message", e.getMessage());
        body.put("hint", "No yearly partition schema exists for the requested year/tenant; "
                + "verify the year filter or contact data ops to provision the partition.");
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }
}

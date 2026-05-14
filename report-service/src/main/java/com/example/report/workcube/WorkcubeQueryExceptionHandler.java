package com.example.report.workcube;

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
 * Phase 2 Program 11.2b — maps {@link WorkcubeQuerySecurityException} to
 * a deterministic 403 response (Adım 11.2b).
 */
@RestControllerAdvice
public class WorkcubeQueryExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(WorkcubeQueryExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public WorkcubeQueryExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(WorkcubeQuerySecurityException.class)
    public ResponseEntity<JsonNode> handle(WorkcubeQuerySecurityException e) {
        log.warn("workcube_query_security_violation: report={} message={}",
                e.reportKey(), e.getMessage());
        ObjectNode body = objectMapper.createObjectNode();
        body.put("error", "workcube_query_security_violation");
        body.put("reportKey", e.reportKey());
        body.put("message", e.getMessage());
        body.put("hint", "Rendered SQL allowlist check failed at execution time. "
                + "Verify ReportingAllowlist.V1 includes the referenced tables.");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }
}

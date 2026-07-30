package com.example.report.workcube;

import com.example.commonauth.scope.ScopeContext;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/reports/project-actuals/provider")
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class ProjectActualProviderController {
    private final ProjectActualProviderService service;

    public ProjectActualProviderController(ProjectActualProviderService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('SCOPE_budget:read','SCOPE_budget:write')")
    public ResponseEntity<?> find(
            @RequestHeader(
                    value = CompanyHeaderScopeNarrower.HEADER_NAME,
                    required = false) String companyHeader,
            @RequestParam long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1000") int limit) {
        long companyId = parseCompany(companyHeader);
        try {
            return ResponseEntity.ok(service.findAuthorized(
                    ScopeContext.current(), companyId, projectId, from, to, cursor, limit));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "mssql_unavailable",
                            "op", "projectActualProvider",
                            "message", "Workcube actuals are temporarily unavailable.",
                            "retryAfterSec", 30));
        }
    }

    @GetMapping(value = "/source-lines", produces = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasAnyAuthority('SCOPE_budget:read','SCOPE_budget:write')")
    public ResponseEntity<?> findSourceLines(
            @RequestHeader(
                    value = CompanyHeaderScopeNarrower.HEADER_NAME,
                    required = false) String companyHeader,
            @RequestParam long projectId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor,
            @RequestParam(defaultValue = "1000") int limit) {
        long companyId = parseCompany(companyHeader);
        try {
            return ResponseEntity.ok(service.findAuthorizedSourceLines(
                    ScopeContext.current(), companyId, projectId, from, to, cursor, limit));
        } catch (DataAccessException unavailable) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "mssql_unavailable",
                            "op", "projectActualSourceLineProvider",
                            "message", "Workcube source lines are temporarily unavailable.",
                            "retryAfterSec", 30));
        }
    }

    private long parseCompany(String value) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Company-Id is required");
        }
        try {
            long companyId = Long.parseLong(value.trim());
            if (companyId < 1) {
                throw new NumberFormatException("non-positive");
            }
            return companyId;
        } catch (NumberFormatException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Company-Id must be a positive number");
        }
    }
}

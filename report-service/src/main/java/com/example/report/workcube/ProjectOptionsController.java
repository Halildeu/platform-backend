package com.example.report.workcube;

import com.example.commonauth.scope.ScopeContext;
import com.example.report.authz.CompanyHeaderScopeNarrower;
import java.util.List;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Authenticated, company-scoped, read-only Workcube project picker.
 */
@RestController
@RequestMapping("/api/v1/reports/project-options")
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class ProjectOptionsController {

    private final ProjectOptionsService service;

    public ProjectOptionsController(ProjectOptionsService service) {
        this.service = service;
    }

    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> list(
            @RequestHeader(
                    value = CompanyHeaderScopeNarrower.HEADER_NAME,
                    required = false) String companyHeader) {
        if (companyHeader == null || companyHeader.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Company-Id is required");
        }
        long companyId;
        try {
            companyId = Long.parseLong(companyHeader.trim());
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Company-Id must be numeric");
        }

        try {
            List<ProjectOptionsRepository.ProjectOption> projects =
                    service.findAuthorized(ScopeContext.current(), companyId);
            return ResponseEntity.ok(projects);
        } catch (DataAccessException ex) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "mssql_unavailable",
                            "op", "projectOptions",
                            "message", "Workcube MSSQL temporarily unreachable; PG-backed reports OK.",
                            "retryAfterSec", 30));
        }
    }
}

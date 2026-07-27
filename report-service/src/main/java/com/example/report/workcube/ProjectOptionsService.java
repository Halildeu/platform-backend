package com.example.report.workcube;

import com.example.commonauth.scope.ScopeContext;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class ProjectOptionsService {

    private final ProjectOptionsRepository repository;

    public ProjectOptionsService(ProjectOptionsRepository repository) {
        this.repository = repository;
    }

    public List<ProjectOptionsRepository.ProjectOption> findAuthorized(
            ScopeContext scope,
            long companyId) {
        if (scope == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        if (companyId < 1) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "X-Company-Id must be a positive number");
        }
        if (!scope.superAdmin() && !scope.allowedCompanyIds().contains(companyId)) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Company is not in the caller scope");
        }

        List<ProjectOptionsRepository.ProjectOption> projects =
                repository.findByCompanyId(companyId);
        if (scope.superAdmin()) {
            return projects;
        }

        Set<Long> allowedProjects = scope.allowedProjectIds();
        if (allowedProjects == null || allowedProjects.isEmpty()) {
            // The money endpoint enforces PROJECT scope. Keep the picker
            // aligned with that fail-closed boundary: a company grant alone
            // must not reveal/select project-level accounting actuals.
            return Collections.emptyList();
        }
        return projects.stream()
                .filter(project -> allowedProjects.contains(project.id()))
                .toList();
    }
}

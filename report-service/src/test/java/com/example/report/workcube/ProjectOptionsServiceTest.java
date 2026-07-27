package com.example.report.workcube;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.when;

import com.example.commonauth.scope.ScopeContext;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class ProjectOptionsServiceTest {

    @Mock ProjectOptionsRepository repository;
    private ProjectOptionsService service;

    private static final List<ProjectOptionsRepository.ProjectOption> PROJECTS = List.of(
            new ProjectOptionsRepository.ProjectOption(10L, "P10", "Project 10", 35L, true),
            new ProjectOptionsRepository.ProjectOption(11L, "P11", "Project 11", 35L, false));

    @BeforeEach
    void setUp() {
        service = new ProjectOptionsService(repository);
    }

    @Test
    void superAdminGetsCompanyProjects() {
        when(repository.findByCompanyId(35L)).thenReturn(PROJECTS);
        assertEquals(PROJECTS,
                service.findAuthorized(ScopeContext.superAdmin("admin@example.com"), 35L));
    }

    @Test
    void scopedUserCannotCrossCompanyBoundary() {
        ScopeContext scope = new ScopeContext(
                "user@example.com", Set.of(35L), Set.of(), Set.of(), false);
        assertThrows(ResponseStatusException.class,
                () -> service.findAuthorized(scope, 36L));
    }

    @Test
    void explicitProjectScopeNarrowsCompanyCatalog() {
        when(repository.findByCompanyId(35L)).thenReturn(PROJECTS);
        ScopeContext scope = new ScopeContext(
                "user@example.com", Set.of(35L), Set.of(11L), Set.of(), false);
        assertEquals(List.of(PROJECTS.get(1)), service.findAuthorized(scope, 35L));
    }

    @Test
    void companyScopeWithoutProjectGrantReturnsNoProjects() {
        when(repository.findByCompanyId(35L)).thenReturn(PROJECTS);
        ScopeContext scope = new ScopeContext(
                "user@example.com", Set.of(35L), Set.of(), Set.of(), false);
        assertEquals(List.of(), service.findAuthorized(scope, 35L));
    }
}

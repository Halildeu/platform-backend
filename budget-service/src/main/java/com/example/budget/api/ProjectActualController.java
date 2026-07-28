package com.example.budget.api;

import static com.example.budget.api.ProjectActualDtos.*;

import com.example.budget.security.BudgetActor;
import com.example.budget.security.BudgetActorResolver;
import com.example.budget.service.ProjectActualService;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/budgets/projects")
public class ProjectActualController {
    private final ProjectActualService service;
    private final BudgetActorResolver actors;

    public ProjectActualController(
            ProjectActualService service,
            BudgetActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_budget:write')")
    ProjectBindingView createBinding(
            @RequestHeader("X-Company-Id") long companyId,
            @Valid @RequestBody CreateProjectBindingRequest request,
            Authentication authentication) {
        return service.createBinding(actors.resolve(authentication, companyId), request);
    }

    @PutMapping("/cost-rules")
    @PreAuthorize("hasAuthority('SCOPE_budget:approve')")
    CostRuleSetView replaceCostRules(
            @RequestHeader("X-Company-Id") long companyId,
            @Valid @RequestBody ReplaceCostRulesRequest request,
            Authentication authentication) {
        return service.replaceAndActivateRules(
                actors.resolve(authentication, companyId), request);
    }

    @PostMapping("/{bindingId}/actuals/sync")
    @PreAuthorize("hasAuthority('SCOPE_budget:write')")
    ProjectActualSyncResult sync(
            @RequestHeader("X-Company-Id") long companyId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @PathVariable UUID bindingId,
            @Valid @RequestBody ProjectActualSyncRequest request,
            Authentication authentication) {
        BudgetActor actor = actors.resolve(authentication, companyId);
        return service.sync(actor, bindingId, request, authorization);
    }

    @GetMapping("/{bindingId}/actuals")
    @PreAuthorize("hasAuthority('SCOPE_budget:read')")
    List<ProjectActualRow> rows(
            @RequestHeader("X-Company-Id") long companyId,
            @PathVariable UUID bindingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(defaultValue = "1000") int limit,
            Authentication authentication) {
        return service.rows(
                actors.resolve(authentication, companyId), bindingId, from, to, limit);
    }

    @GetMapping("/{bindingId}/actuals/summary")
    @PreAuthorize("hasAuthority('SCOPE_budget:read')")
    ProjectActualSummary summary(
            @RequestHeader("X-Company-Id") long companyId,
            @PathVariable UUID bindingId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        return service.summary(
                actors.resolve(authentication, companyId), bindingId, from, to);
    }
}

package com.example.budget.api;

import static com.example.budget.api.WorkcubePlanImportDtos.PlanImportRequest;
import static com.example.budget.api.WorkcubePlanImportDtos.PlanImportResult;

import com.example.budget.security.BudgetActorResolver;
import com.example.budget.service.WorkcubePlanImportService;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/budgets/import")
public class WorkcubePlanImportController {
    private final WorkcubePlanImportService service;
    private final BudgetActorResolver actors;

    public WorkcubePlanImportController(
            WorkcubePlanImportService service,
            BudgetActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping("/workcube")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_budget:write') and hasAuthority('ROLE_BUDGET_PLANNER')")
    PlanImportResult importPlans(
            @RequestHeader("X-Company-Id") long companyId,
            @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization,
            @Valid @RequestBody PlanImportRequest request,
            Authentication authentication) {
        return service.importPlans(
                actors.resolve(authentication, companyId), request, authorization);
    }
}

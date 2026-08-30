package com.example.budget.api;

import static com.example.budget.api.BudgetDtos.*;

import com.example.budget.security.BudgetActor;
import com.example.budget.security.BudgetActorResolver;
import com.example.budget.service.BudgetService;
import jakarta.validation.Valid;
import java.util.UUID;
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
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/budgets")
public class BudgetController {
    private final BudgetService service;
    private final BudgetActorResolver actors;

    public BudgetController(BudgetService service, BudgetActorResolver actors) {
        this.service = service;
        this.actors = actors;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('SCOPE_budget:write')")
    BudgetPlanView create(
            @RequestHeader("X-Company-Id") long companyId,
            @Valid @RequestBody CreateBudgetRequest request,
            Authentication authentication) {
        if (request.companyId() != companyId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Header and payload company differ");
        }
        BudgetActor actor = actors.resolve(authentication, companyId);
        return service.create(actor, request);
    }

    @PutMapping("/{planId}/versions/{versionId}/lines")
    @PreAuthorize("hasAuthority('SCOPE_budget:write')")
    BudgetPlanView replaceLines(
            @RequestHeader("X-Company-Id") long companyId,
            @PathVariable UUID planId,
            @PathVariable UUID versionId,
            @Valid @RequestBody ReplaceLinesRequest request,
            Authentication authentication) {
        return service.replaceLines(actors.resolve(authentication, companyId), planId, versionId, request);
    }

    @PostMapping("/{planId}/versions/{versionId}/submit")
    @PreAuthorize("hasAuthority('SCOPE_budget:write')")
    BudgetPlanView submit(
            @RequestHeader("X-Company-Id") long companyId,
            @PathVariable UUID planId,
            @PathVariable UUID versionId,
            Authentication authentication) {
        return service.submit(actors.resolve(authentication, companyId), planId, versionId);
    }

    @PostMapping("/{planId}/versions/{versionId}/approve")
    @PreAuthorize("hasAuthority('SCOPE_budget:approve')")
    BudgetPlanView approve(
            @RequestHeader("X-Company-Id") long companyId,
            @PathVariable UUID planId,
            @PathVariable UUID versionId,
            Authentication authentication) {
        return service.approve(actors.resolve(authentication, companyId), planId, versionId);
    }

    @GetMapping("/plans/current")
    @PreAuthorize("hasAuthority('SCOPE_budget:read')")
    BudgetPlanView current(
            @RequestHeader("X-Company-Id") long companyId,
            @RequestParam int fiscalYear,
            Authentication authentication) {
        return service.current(actors.resolve(authentication, companyId), fiscalYear);
    }

    @GetMapping("/{planId}/versions/{versionId}")
    @PreAuthorize("hasAuthority('SCOPE_budget:read')")
    BudgetPlanView get(
            @RequestHeader("X-Company-Id") long companyId,
            @PathVariable UUID planId,
            @PathVariable UUID versionId,
            Authentication authentication) {
        return service.get(actors.resolve(authentication, companyId), planId, versionId);
    }

    @GetMapping("/{planId}/versions/{versionId}/control")
    @PreAuthorize("hasAuthority('SCOPE_budget:read')")
    BudgetControlSummary control(
            @RequestHeader("X-Company-Id") long companyId,
            @PathVariable UUID planId,
            @PathVariable UUID versionId,
            Authentication authentication) {
        return service.control(actors.resolve(authentication, companyId), planId, versionId);
    }
}

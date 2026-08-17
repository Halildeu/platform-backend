package com.example.report.contract.rules;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ActionDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.ArrayList;
import java.util.List;

/**
 * RC-010 — a destructive action must declare its own permission and a
 * confirmation payload.
 *
 * <p>Phase 2 Program 3 spec §3.3: {@code destructive=true} actions cannot
 * inherit REPORT_VIEW ({@code permission} must be explicit) and must carry a
 * {@code confirm} payload (modal text) so the frontend can never render a
 * one-click destructive button. Implemented for issue #799 (1c migration).
 */
public final class RC010DestructiveActionRequiresPermissionAndConfirm implements ContractRule {

    @Override
    public String ruleId() {
        return "RC-010";
    }

    @Override
    public List<ContractViolation> validate(ReportDefinition def) {
        if (def.actions() == null) {
            return List.of();
        }
        List<ContractViolation> violations = new ArrayList<>();
        for (ActionDefinition action : def.actions()) {
            if (!action.isDestructive()) {
                continue;
            }
            if (action.permission() == null || action.permission().isBlank()) {
                violations.add(ContractViolation.fail(
                        ruleId(), def.key(), "actions[" + action.id() + "].permission",
                        "destructive action must declare an explicit permission "
                                + "(REPORT_VIEW inherit is forbidden for destructive actions)"));
            }
            if (action.confirm() == null || action.confirm().isBlank()) {
                violations.add(ContractViolation.fail(
                        ruleId(), def.key(), "actions[" + action.id() + "].confirm",
                        "destructive action must declare a confirm payload "
                                + "(modal text; one-click destructive rendering is forbidden)"));
            }
        }
        return violations;
    }
}

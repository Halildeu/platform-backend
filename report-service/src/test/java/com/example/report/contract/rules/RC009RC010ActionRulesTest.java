package com.example.report.contract.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.contract.report.ContractViolation;
import com.example.report.registry.ActionDefinition;
import com.example.report.registry.ColumnDefinition;
import com.example.report.registry.ReportDefinition;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Positive + negative coverage for the RC-009/RC-010 real implementations
 * (issue #799 — the rules were deferred no-op stubs until the actions[] 1c
 * migration; their registry entries are gone, so a regression back to a stub
 * body trips {@code ContractRuleStubDetectorTest}).
 */
class RC009RC010ActionRulesTest {

    private final RC009ActionScopeValid rc009 = new RC009ActionScopeValid();
    private final RC010DestructiveActionRequiresPermissionAndConfirm rc010 =
            new RC010DestructiveActionRequiresPermissionAndConfirm();

    @Test
    void reportsWithoutActionsPassBothRules() {
        ReportDefinition def = report((List<ActionDefinition>) null);

        assertThat(rc009.validate(def)).isEmpty();
        assertThat(rc010.validate(def)).isEmpty();
    }

    @Test
    void validScopesAndGuardedDestructiveActionPass() {
        ReportDefinition def = report(List.of(
                action("export", "grid", null, null, null),
                action("open", "row", false, null, null),
                action("bulk-close", "selection", true, "REPORT_ACTION_CLOSE",
                        "Seçili kayıtlar kapatılacak. Emin misiniz?")));

        assertThat(rc009.validate(def)).isEmpty();
        assertThat(rc010.validate(def)).isEmpty();
    }

    @Test
    void invalidAndBlankScopesAreEnumViolations() {
        ReportDefinition def = report(List.of(
                action("popup", "modal", null, null, null),
                action("blank", "  ", null, null, null)));

        List<ContractViolation> violations = rc009.validate(def);
        assertThat(violations).hasSize(2);
        assertThat(violations).allSatisfy(v ->
                assertThat(v.ruleId()).isEqualTo("RC-009"));
        assertThat(violations.getFirst().message()).contains("ENUM_VIOLATION");
    }

    @Test
    void destructiveActionWithoutPermissionAndConfirmFailsBoth() {
        ReportDefinition def = report(List.of(
                action("purge", "selection", true, null, null)));

        List<ContractViolation> violations = rc010.validate(def);
        assertThat(violations).hasSize(2);
        assertThat(violations).extracting(ContractViolation::field)
                .containsExactlyInAnyOrder(
                        "actions[purge].permission", "actions[purge].confirm");
    }

    @Test
    void destructiveActionWithBlankConfirmStillFails() {
        ReportDefinition def = report(List.of(
                action("purge", "row", true, "REPORT_ACTION_PURGE", "   ")));

        List<ContractViolation> violations = rc010.validate(def);
        assertThat(violations).hasSize(1);
        assertThat(violations.getFirst().field()).isEqualTo("actions[purge].confirm");
    }

    private static ActionDefinition action(
            String id, String scope, Boolean destructive, String permission, String confirm) {
        return new ActionDefinition(id, id, scope, destructive, permission, confirm);
    }

    private static ReportDefinition report(List<ActionDefinition> actions) {
        return new ReportDefinition(
                "rc-action-test", "1.0", "Action Test", null, "Test",
                "SOME_TABLE", "dbo", "static", null, null,
                List.of(new ColumnDefinition("ID", "Id", "number", null, false, false, false, null)),
                null, null, null,
                null, null, null, null, actions);
    }
}

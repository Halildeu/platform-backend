package com.example.report.contract.report;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * R16 PR-C-2 (Codex 019e27f5 P2 absorb) — ContractGateSummary WARN visibility test.
 *
 * <p>Önceki tasarım sadece FAIL'leri JSON/Markdown'a yansıtıyordu. Bu test
 * suite WARN'lerin de görünür hale gelmesini doğrular — RC-012
 * AuthzReferenceCheck gibi WARN-first rule'ların sticky comment'te
 * görüntülenmesi R16 close-out discipline guard'ının completing piece'i.
 */
class ContractGateSummaryWarnVisibilityTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-05-15T00:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void unsuppressedWarnings_listsAllWarns_sorted() {
        ContractGateSummary summary = summaryWith(
                ContractViolation.warn("RC-012", "rep-b", "access.reportGroup", "warn b"),
                ContractViolation.warn("RC-012", "rep-a", "access.reportGroup", "warn a"),
                ContractViolation.fail("RC-001", "rep-c", "yearColumn", "fail c"));

        List<ContractViolation> warns = summary.unsuppressedWarnings();

        assertThat(warns).hasSize(2);
        // Sorted by reportKey within same ruleId
        assertThat(warns.get(0).reportKey()).isEqualTo("rep-a");
        assertThat(warns.get(1).reportKey()).isEqualTo("rep-b");
        // FAIL excluded from warnings list
        assertThat(warns).noneMatch(v -> v.severity() == ContractViolation.Severity.FAIL);
    }

    @Test
    void warningsByRule_groupsAndCounts() {
        ContractGateSummary summary = summaryWith(
                ContractViolation.warn("RC-012", "rep-a", "access.reportGroup", "a"),
                ContractViolation.warn("RC-012", "rep-b", "access.reportGroup", "b"),
                ContractViolation.warn("RC-007", "rep-c", "ghost_field", "c"));

        assertThat(summary.warningsByRule())
                .containsEntry("RC-012", 2L)
                .containsEntry("RC-007", 1L);
    }

    @Test
    void toJson_includesWarningFields() throws Exception {
        ContractGateSummary summary = summaryWith(
                ContractViolation.warn("RC-012", "rep-a", "access.reportGroup", "warn message"));

        String json = summary.toJson(mapper);

        assertThat(json).contains("\"unsuppressedWarningCount\" : 1");
        assertThat(json).contains("\"warningsByRule\"");
        assertThat(json).contains("\"RC-012\" : 1");
        assertThat(json).contains("\"unsuppressedWarnings\"");
        assertThat(json).contains("warn message");
    }

    @Test
    void toMarkdown_includesWarningSection_whenWarnsPresent() {
        ContractGateSummary summary = summaryWith(
                ContractViolation.warn("RC-012", "fin-bank", "access.reportGroup",
                        "reportGroup='FINANCE_REPORTS' not in canonical model"));

        String md = summary.toMarkdown(clock);

        assertThat(md).contains("## :warning: Unsuppressed Warnings");
        assertThat(md).contains("`RC-012`");
        assertThat(md).contains("`fin-bank`");
        assertThat(md).contains("close-out discipline");
        assertThat(md).contains("deferred-stub-rules.yaml");
    }

    @Test
    void toMarkdown_omitsWarningSection_whenNoWarns() {
        ContractGateSummary summary = summaryWith(
                ContractViolation.fail("RC-001", "rep-a", "yearColumn", "fail"));

        String md = summary.toMarkdown(clock);

        assertThat(md).doesNotContain("Unsuppressed Warnings");
    }

    @Test
    void isGreen_remainsTrueWhenOnlyWarnsPresent() {
        // R16 PR-C-2: WARN-first kontrat — WARN'ler CI'yı kırmaz, ama görünür olur.
        ContractGateSummary summary = summaryWith(
                ContractViolation.warn("RC-012", "rep-a", "access.reportGroup", "warn"));

        assertThat(summary.isGreen()).isTrue();
        assertThat(summary.unsuppressedFailures()).isEmpty();
        assertThat(summary.unsuppressedWarnings()).hasSize(1);
    }

    private ContractGateSummary summaryWith(ContractViolation... violations) {
        return new ContractGateSummary(
                violations.length,
                List.of(violations),
                List.of(violations),
                List.of(),
                List.of(),
                Instant.parse("2026-05-15T00:00:00Z"));
    }
}

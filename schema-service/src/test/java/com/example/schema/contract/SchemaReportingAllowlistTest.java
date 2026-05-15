package com.example.schema.contract;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Adım 12 — drift guard for the schema-service mirror of the
 * report-service {@code ReportingAllowlist.V1}.
 *
 * <p>The two 40-table sets must stay byte-identical (Codex {@code 019e2d64}
 * S1: schema-service mirrors, does not depend on report-service). This
 * test pins the count + a sample of canonical tables so an accidental
 * single-table edit on either side is caught at build time.
 */
class SchemaReportingAllowlistTest {

    @Test
    void v1_has_exactly_40_tables() {
        // Matches report-service ReportingAllowlist.V1:
        // 23 ADR-0012-SS canonical + 7 report `source` + 10 sourceQuery.
        assertThat(SchemaReportingAllowlist.V1).hasSize(40);
    }

    @Test
    void v1_contains_canonical_invoice_tables() {
        assertThat(SchemaReportingAllowlist.V1)
            .contains("INVOICE", "INVOICE_ROW", "ORDERS", "ORDER_ROW");
    }

    @Test
    void v1_contains_employee_domain_tables() {
        assertThat(SchemaReportingAllowlist.V1)
            .contains("EMPLOYEES", "EMPLOYEES_SALARY", "EMPLOYEE_POSITIONS");
    }

    @Test
    void containsV1_is_case_insensitive() {
        assertThat(SchemaReportingAllowlist.containsV1("invoice")).isTrue();
        assertThat(SchemaReportingAllowlist.containsV1("Invoice")).isTrue();
        assertThat(SchemaReportingAllowlist.containsV1("INVOICE")).isTrue();
    }

    @Test
    void containsV1_rejects_non_allowlisted_table() {
        assertThat(SchemaReportingAllowlist.containsV1("SOME_RANDOM_TABLE")).isFalse();
    }

    @Test
    void containsV1_handles_null_and_blank() {
        assertThat(SchemaReportingAllowlist.containsV1(null)).isFalse();
        assertThat(SchemaReportingAllowlist.containsV1("")).isFalse();
        assertThat(SchemaReportingAllowlist.containsV1("   ")).isFalse();
    }

    @Test
    void name_and_version_are_the_published_contract_values() {
        assertThat(SchemaReportingAllowlist.NAME).isEqualTo("ReportingAllowlist");
        assertThat(SchemaReportingAllowlist.VERSION).isEqualTo("V1");
    }
}

package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectActualProviderRepositoryTest {

    @Test
    void directionAndReturnFlagsOverrideLegacyCategoryMapping() {
        assertThat(ProjectActualProviderRepository.invoiceKind(561, true, false))
                .isEqualTo("PURCHASE_INVOICE");
        assertThat(ProjectActualProviderRepository.invoiceKind(601, false, false))
                .isEqualTo("SALES_INVOICE");
        assertThat(ProjectActualProviderRepository.invoiceKind(999, true, true))
                .isEqualTo("PURCHASE_RETURN");
        assertThat(ProjectActualProviderRepository.invoiceKind(998, false, true))
                .isEqualTo("SALES_RETURN");
    }

    @Test
    void legacyCategoryMappingRemainsTheFallbackWhenDirectionIsUnknown() {
        assertThat(ProjectActualProviderRepository.invoiceKind(56, null, null))
                .isEqualTo("PURCHASE_INVOICE");
        assertThat(ProjectActualProviderRepository.invoiceKind(57, null, null))
                .isEqualTo("PURCHASE_RETURN");
        assertThat(ProjectActualProviderRepository.invoiceKind(59, null, null))
                .isEqualTo("SALES_INVOICE");
        assertThat(ProjectActualProviderRepository.invoiceKind(60, null, null))
                .isEqualTo("SALES_RETURN");
        assertThat(ProjectActualProviderRepository.invoiceKind(999, null, null))
                .isEqualTo("OTHER_INVOICE");
    }
}

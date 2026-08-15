package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.workcube.BudgetPlanProviderDtos.BudgetPlanRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pins the provider's canonical hash-input format. budget-service re-implements
 * the same format in {@code WorkcubePlanImportService.providerHash} and its
 * {@code WorkcubePlanImportHashContractTest} pins the identical literal — if
 * either side changes shape, exactly one of the two tests goes red.
 */
class BudgetPlanProviderHashContractTest {

    static final String PINNED_CANONICAL =
            "WORKCUBE|35|2026|9|2026 Opex|1|false|40|17|2026-03-15|740.01"
                    + "|12|3|null|44200|5|7|2|0.00|1500.00|Bakım bütçesi";

    @Test
    void canonicalStringMatchesThePinnedContract() {
        assertThat(BudgetPlanProviderService.canonical(fixtureRow()))
                .isEqualTo(PINNED_CANONICAL);
    }

    static BudgetPlanRow fixtureRow() {
        return new BudgetPlanRow(
                "WORKCUBE", 35L, 2026, 9L, "2026 Opex", 1, false,
                40L, 17L, LocalDate.of(2026, 3, 15), "740.01",
                12L, 3L, null, 44200L, 5L, 7L, 2L,
                new BigDecimal("0.00"), new BigDecimal("1500.00"),
                "Bakım bütçesi", null);
    }
}

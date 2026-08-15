package com.example.budget.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.budget.api.WorkcubePlanImportDtos.ProviderBudgetPlanRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * budget-service side of the provider-hash contract. The literal below is
 * pinned identically in report-service {@code BudgetPlanProviderHashContractTest};
 * a format change on either side turns exactly one of the two tests red.
 */
class WorkcubePlanImportHashContractTest {

    static final String PINNED_CANONICAL =
            "WORKCUBE|35|2026|9|2026 Opex|1|false|40|17|2026-03-15|740.01"
                    + "|12|3|null|44200|5|7|2|0.00|1500.00|Bakım bütçesi";

    @Test
    void providerHashEqualsSha256OfThePinnedCanonical() throws Exception {
        String expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256")
                        .digest(PINNED_CANONICAL.getBytes(StandardCharsets.UTF_8)));

        assertThat(WorkcubePlanImportService.providerHash(fixtureRow()))
                .isEqualTo(expected);
    }

    static ProviderBudgetPlanRow fixtureRow() {
        return new ProviderBudgetPlanRow(
                "WORKCUBE", 35L, 2026, 9L, "2026 Opex", 1, false,
                40L, 17L, LocalDate.of(2026, 3, 15), "740.01",
                12L, 3L, null, 44200L, 5L, 7L, 2L,
                new BigDecimal("0.00"), new BigDecimal("1500.00"),
                "Bakım bütçesi", null);
    }
}

package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.report.workcube.PypActualProviderDtos.PypActualRow;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Pins the PYP actual provider's canonical hash-input format (gitops#3496
 * slice B). A consumer that re-verifies row integrity must reproduce this
 * literal verbatim; a shape change turns exactly this test red first.
 */
class PypActualProviderHashContractTest {

    static final String PINNED_CANONICAL =
            "WORKCUBE|2026|1|9001|70001|2026-03-15|740.01|DEBIT|1500.00|TRY"
                    + "|56|4001|INVOICE|FTR-2026-17|false|INVOICE_LINE"
                    + "|12|PYP.01.02|Kaba İşler|001.002|77|Kalıp İşçiliği|5"
                    + "|44200|4001|88001|3501|null|null";

    @Test
    void canonicalStringMatchesThePinnedContract() {
        assertThat(PypActualProviderService.canonical(fixtureRow()))
                .isEqualTo(PINNED_CANONICAL);
    }

    static PypActualRow fixtureRow() {
        return new PypActualRow(
                "WORKCUBE", 2026, 1L, 9001L, 70001L,
                LocalDate.of(2026, 3, 15), "740.01", "DEBIT",
                new BigDecimal("1500.00"), "TRY",
                56, 4001L, "INVOICE", "FTR-2026-17", false,
                "INVOICE_LINE",
                12L, "PYP.01.02", "Kaba İşler", "001.002",
                77L, "Kalıp İşçiliği", 5L,
                44200L, 4001L, 88001L, 3501L, null, null,
                null);
    }
}

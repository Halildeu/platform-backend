package com.example.report.workcube;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class ProjectActualProviderDtos {
    private ProjectActualProviderDtos() {
    }

    public record ProjectActualRow(
            String sourceSystem,
            int sourceLedgerYear,
            long sourceCompanyId,
            long sourceProjectId,
            long journalCardId,
            long journalRowId,
            LocalDate postingDate,
            String accountCode,
            String debitCredit,
            BigDecimal signedAmount,
            String currency,
            Integer actionType,
            Long actionId,
            String documentType,
            String documentNo,
            String resolutionStatus,
            boolean cancelled,
            String sourceHash) {
    }

    public record ProjectActualPage(
            List<ProjectActualRow> rows,
            String nextCursor,
            boolean hasMore) {
    }
}

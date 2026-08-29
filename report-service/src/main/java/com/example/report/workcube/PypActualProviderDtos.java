package com.example.report.workcube;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Wire contracts for the PYP-labeled actuals provider (gitops#3496 slice B).
 *
 * <p>The ledger (muavin, {@code ACCOUNT_CARD_ROWS}) is the spine: every
 * official actual is one ledger row here, so completeness and duplicate
 * detection reduce to comparing against a single source. Each row is then
 * labeled with the budget dimensions (bütçe kalemi = {@code EXPENSE_ITEMS},
 * bütçe kategorisi/PYP = {@code EXPENSE_CENTER} with its {@code HIERARCHY})
 * resolved from the SOURCE document line that produced the ledger row —
 * invoice lines carry {@code ROW_EXP_ITEM_ID}/{@code ROW_EXP_CENTER_ID},
 * expense (masraf) lines carry {@code EXPENSE_ITEM_ID}/{@code
 * EXPENSE_CENTER_ID} — because the source entry screens are where planners
 * actually pick those dimensions (measured: gitops#3496 slice A).
 */
public final class PypActualProviderDtos {
    private PypActualProviderDtos() {
    }

    /**
     * How the budget dimensions on this ledger row were resolved.
     *
     * <ul>
     *   <li>{@code INVOICE_LINE} — exact invoice line ({@code ROW_EXP_*}).</li>
     *   <li>{@code INVOICE_UNIFORM} — the ledger row could not be tied to one
     *       invoice line ({@code ACTION_ROW_ID} is unset in this tenant —
     *       measured live 2026-08-29: 0 of 2898 sampled invoice rows carried
     *       it), but every line of the invoice agrees on one (center, item)
     *       pair (99.6%% of 2026 invoices do); that pair is used, and a
     *       single-order invoice also contributes its order lineage.</li>
     *   <li>{@code INVOICE_MIXED} — invoice lines disagree; nothing is
     *       guessed, dimensions stay null.</li>
     *   <li>{@code INVOICE_HEADER} — invoice header fallback.</li>
     *   <li>{@code EXPENSE_UNIFORM} — every masraf line of the expense agrees
     *       on one (center, item) pair; that pair is used.</li>
     *   <li>{@code EXPENSE_MIXED} — the expense has lines with differing
     *       dimensions; nothing is guessed, dimensions stay null.</li>
     *   <li>{@code NONE} — no source-side dimension exists (bank, cari,
     *       manual journal, transfer, unresolved).</li>
     * </ul>
     */
    public record PypActualRow(
            String sourceSystem,
            int sourceLedgerYear,
            long sourceCompanyId,
            long journalCardId,
            long journalRowId,
            LocalDate actionDate,
            String accountCode,
            String debitCredit,
            BigDecimal signedAmount,
            String currency,
            Integer actionType,
            Long actionId,
            String documentType,
            String documentNo,
            boolean cancelled,
            String dimensionSource,
            Long expenseCenterId,
            String expenseCenterCode,
            String expenseCenterName,
            String expenseCenterHierarchy,
            Long expenseItemId,
            String expenseItemName,
            Long expenseCategoryId,
            Long projectId,
            Long invoiceId,
            Long invoiceRowId,
            Long orderId,
            Long progressId,
            Long contractId,
            String rowHash) {
    }

    public record PypActualPage(
            List<PypActualRow> rows,
            String nextCursor,
            boolean hasMore) {
    }
}

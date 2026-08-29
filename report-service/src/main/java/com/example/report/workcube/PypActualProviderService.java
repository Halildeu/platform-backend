package com.example.report.workcube;

import static com.example.report.workcube.PypActualProviderDtos.PypActualPage;
import static com.example.report.workcube.PypActualProviderDtos.PypActualRow;

import com.example.commonauth.scope.ScopeContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@ConditionalOnBean(name = "workcubeMssqlDataSource")
public class PypActualProviderService {
    private static final int MAX_LIMIT = 2000;
    private static final int MIN_FISCAL_YEAR = 2000;
    private static final int MAX_FISCAL_YEAR = 2200;

    private final CompanyOptionsService companyOptions;
    private final PypActualProviderRepository repository;

    public PypActualProviderService(
            CompanyOptionsService companyOptions,
            PypActualProviderRepository repository) {
        this.companyOptions = companyOptions;
        this.repository = repository;
    }

    public PypActualPage findAuthorized(
            ScopeContext scope,
            long companyId,
            int fiscalYear,
            String cursorText,
            int requestedLimit) {
        if (fiscalYear < MIN_FISCAL_YEAR || fiscalYear > MAX_FISCAL_YEAR) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Fiscal year is outside the supported range");
        }
        requireCompanyAccess(scope, companyId);

        int limit = Math.min(Math.max(requestedLimit, 1), MAX_LIMIT);
        long afterRowId = decodeCursor(cursorText, fiscalYear);
        List<PypActualRow> candidates =
                repository.find(companyId, fiscalYear, afterRowId, limit + 1);
        boolean hasMore = candidates.size() > limit;
        List<PypActualRow> page = new ArrayList<>(
                candidates.subList(0, Math.min(candidates.size(), limit)));
        List<PypActualRow> hashed = page.stream().map(this::withHash).toList();
        String nextCursor = hasMore && !hashed.isEmpty()
                ? encodeCursor(hashed.getLast())
                : null;
        return new PypActualPage(hashed, nextCursor, hasMore);
    }

    private PypActualRow withHash(PypActualRow row) {
        return new PypActualRow(
                row.sourceSystem(),
                row.sourceLedgerYear(),
                row.sourceCompanyId(),
                row.journalCardId(),
                row.journalRowId(),
                row.actionDate(),
                row.accountCode(),
                row.debitCredit(),
                row.signedAmount(),
                row.currency(),
                row.actionType(),
                row.actionId(),
                row.documentType(),
                row.documentNo(),
                row.cancelled(),
                row.dimensionSource(),
                row.expenseCenterId(),
                row.expenseCenterCode(),
                row.expenseCenterName(),
                row.expenseCenterHierarchy(),
                row.expenseItemId(),
                row.expenseItemName(),
                row.expenseCategoryId(),
                row.projectId(),
                row.invoiceId(),
                row.invoiceRowId(),
                row.orderId(),
                row.progressId(),
                row.contractId(),
                sha256(canonical(row)));
    }

    /**
     * Canonical hash input — a consumer that re-verifies row integrity must
     * re-implement this format verbatim (same contract discipline as
     * {@link BudgetPlanProviderService#canonical}).
     */
    static String canonical(PypActualRow row) {
        return String.join("|",
                row.sourceSystem(),
                Integer.toString(row.sourceLedgerYear()),
                Long.toString(row.sourceCompanyId()),
                Long.toString(row.journalCardId()),
                Long.toString(row.journalRowId()),
                String.valueOf(row.actionDate()),
                String.valueOf(row.accountCode()),
                row.debitCredit(),
                row.signedAmount().toPlainString(),
                row.currency(),
                String.valueOf(row.actionType()),
                String.valueOf(row.actionId()),
                String.valueOf(row.documentType()),
                String.valueOf(row.documentNo()),
                Boolean.toString(row.cancelled()),
                String.valueOf(row.dimensionSource()),
                String.valueOf(row.expenseCenterId()),
                String.valueOf(row.expenseCenterCode()),
                String.valueOf(row.expenseCenterName()),
                String.valueOf(row.expenseCenterHierarchy()),
                String.valueOf(row.expenseItemId()),
                String.valueOf(row.expenseItemName()),
                String.valueOf(row.expenseCategoryId()),
                String.valueOf(row.projectId()),
                String.valueOf(row.invoiceId()),
                String.valueOf(row.invoiceRowId()),
                String.valueOf(row.orderId()),
                String.valueOf(row.progressId()),
                String.valueOf(row.contractId()));
    }

    private String encodeCursor(PypActualRow row) {
        String value = row.sourceLedgerYear() + "|" + row.journalRowId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private long decodeCursor(String cursorText, int fiscalYear) {
        if (cursorText == null || cursorText.isBlank()) {
            return 0L;
        }
        try {
            String decoded = new String(
                    Base64.getUrlDecoder().decode(cursorText),
                    StandardCharsets.UTF_8);
            int separator = decoded.lastIndexOf('|');
            int cursorYear = Integer.parseInt(decoded.substring(0, separator));
            long rowId = Long.parseLong(decoded.substring(separator + 1));
            if (cursorYear < MIN_FISCAL_YEAR || cursorYear > MAX_FISCAL_YEAR || rowId < 0) {
                throw new IllegalArgumentException("unsafe cursor");
            }
            if (cursorYear != fiscalYear) {
                throw new IllegalArgumentException("cursor fiscal year mismatch");
            }
            return rowId;
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid pyp-actual cursor", invalid);
        }
    }

    private void requireCompanyAccess(ScopeContext scope, long companyId) {
        boolean authorized = companyOptions.findAuthorized(scope).stream()
                .anyMatch(company -> company.id() == companyId);
        if (!authorized) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Company is outside the caller scope");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}

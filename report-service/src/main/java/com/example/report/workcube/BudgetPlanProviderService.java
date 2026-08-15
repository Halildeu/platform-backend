package com.example.report.workcube;

import static com.example.report.workcube.BudgetPlanProviderDtos.BudgetPlanPage;
import static com.example.report.workcube.BudgetPlanProviderDtos.BudgetPlanRow;

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
public class BudgetPlanProviderService {
    private static final int MAX_LIMIT = 2000;
    private static final int MIN_FISCAL_YEAR = 2000;
    private static final int MAX_FISCAL_YEAR = 2200;

    private final CompanyOptionsService companyOptions;
    private final BudgetPlanProviderRepository repository;

    public BudgetPlanProviderService(
            CompanyOptionsService companyOptions,
            BudgetPlanProviderRepository repository) {
        this.companyOptions = companyOptions;
        this.repository = repository;
    }

    public BudgetPlanPage findAuthorized(
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
        BudgetPlanProviderRepository.Cursor cursor = decodeCursor(cursorText, fiscalYear);
        long afterRowId = cursor == null ? 0L : cursor.budgetPlanRowId();
        List<BudgetPlanRow> candidates =
                repository.find(companyId, fiscalYear, afterRowId, limit + 1);
        boolean hasMore = candidates.size() > limit;
        List<BudgetPlanRow> page = new ArrayList<>(
                candidates.subList(0, Math.min(candidates.size(), limit)));
        List<BudgetPlanRow> hashed = page.stream().map(this::withHash).toList();
        String nextCursor = hasMore && !hashed.isEmpty()
                ? encodeCursor(hashed.getLast())
                : null;
        return new BudgetPlanPage(hashed, nextCursor, hasMore);
    }

    private BudgetPlanRow withHash(BudgetPlanRow row) {
        return new BudgetPlanRow(
                row.sourceSystem(),
                row.sourceCompanyId(),
                row.fiscalYear(),
                row.budgetId(),
                row.budgetName(),
                row.budgetStage(),
                row.scenario(),
                row.budgetPlanId(),
                row.budgetPlanRowId(),
                row.planDate(),
                row.accountCode(),
                row.expIncCenterId(),
                row.budgetItemId(),
                row.activityTypeId(),
                row.projectId(),
                row.workgroupId(),
                row.departmentId(),
                row.branchId(),
                row.incomeTotal(),
                row.expenseTotal(),
                row.detail(),
                sha256(canonical(row)));
    }

    /**
     * Canonical hash input — budget-service re-implements this format verbatim
     * in {@code WorkcubePlanImportService.providerHash}; any change here is a
     * breaking change there.
     */
    static String canonical(BudgetPlanRow row) {
        return String.join("|",
                row.sourceSystem(),
                Long.toString(row.sourceCompanyId()),
                Integer.toString(row.fiscalYear()),
                Long.toString(row.budgetId()),
                String.valueOf(row.budgetName()),
                String.valueOf(row.budgetStage()),
                Boolean.toString(row.scenario()),
                Long.toString(row.budgetPlanId()),
                Long.toString(row.budgetPlanRowId()),
                String.valueOf(row.planDate()),
                String.valueOf(row.accountCode()),
                String.valueOf(row.expIncCenterId()),
                String.valueOf(row.budgetItemId()),
                String.valueOf(row.activityTypeId()),
                String.valueOf(row.projectId()),
                String.valueOf(row.workgroupId()),
                String.valueOf(row.departmentId()),
                String.valueOf(row.branchId()),
                row.incomeTotal().toPlainString(),
                row.expenseTotal().toPlainString(),
                String.valueOf(row.detail()));
    }

    private String encodeCursor(BudgetPlanRow row) {
        String value = row.fiscalYear() + "|" + row.budgetPlanRowId();
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private BudgetPlanProviderRepository.Cursor decodeCursor(
            String cursorText, int fiscalYear) {
        if (cursorText == null || cursorText.isBlank()) {
            return null;
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
            return new BudgetPlanProviderRepository.Cursor(cursorYear, rowId);
        } catch (RuntimeException invalid) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Invalid budget-plan cursor", invalid);
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

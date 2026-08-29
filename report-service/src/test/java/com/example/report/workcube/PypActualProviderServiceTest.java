package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.example.commonauth.scope.ScopeContext;
import com.example.report.workcube.CompanyOptionsRepository.CompanyOption;
import com.example.report.workcube.PypActualProviderDtos.PypActualPage;
import com.example.report.workcube.PypActualProviderDtos.PypActualRow;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

class PypActualProviderServiceTest {

    private CompanyOptionsService companyOptions;
    private PypActualProviderRepository repository;
    private PypActualProviderService service;
    private ScopeContext scope;

    @BeforeEach
    void setUp() {
        companyOptions = mock(CompanyOptionsService.class);
        repository = mock(PypActualProviderRepository.class);
        service = new PypActualProviderService(companyOptions, repository);
        scope = mock(ScopeContext.class);
        when(companyOptions.findAuthorized(scope))
                .thenReturn(List.of(new CompanyOption(1, "MIK", "Mikrolink")));
    }

    @Test
    void rejectsFiscalYearOutsideTheSupportedRange() {
        assertThatThrownBy(() -> service.findAuthorized(scope, 1L, 1999, null, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void refusesCompaniesOutsideTheCallerScope() {
        assertThatThrownBy(() -> service.findAuthorized(scope, 35L, 2026, null, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void rejectsCursorsMintedForAnotherFiscalYear() {
        String foreignCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2025|70001".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.findAuthorized(scope, 1L, 2026, foreignCursor, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class, error ->
                        assertThat(error.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void hashesEveryRowAndEmitsAKeysetCursorOnlyWhenMoreRowsExist() {
        PypActualRow first = PypActualProviderHashContractTest.fixtureRow();
        PypActualRow second = withJournalRow(first, 70002L);
        when(repository.find(eq(1L), eq(2026), anyLong(), anyInt()))
                .thenReturn(List.of(first, second));

        PypActualPage page = service.findAuthorized(scope, 1L, 2026, null, 1);

        assertThat(page.rows()).hasSize(1);
        assertThat(page.hasMore()).isTrue();
        assertThat(page.rows().getFirst().rowHash()).hasSize(64);
        String decoded = new String(
                Base64.getUrlDecoder().decode(page.nextCursor()),
                StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo("2026|70001");

        when(repository.find(eq(1L), eq(2026), eq(70001L), anyInt()))
                .thenReturn(List.of(second));
        PypActualPage lastPage =
                service.findAuthorized(scope, 1L, 2026, page.nextCursor(), 10);
        assertThat(lastPage.hasMore()).isFalse();
        assertThat(lastPage.nextCursor()).isNull();
    }

    private static PypActualRow withJournalRow(PypActualRow row, long journalRowId) {
        return new PypActualRow(
                row.sourceSystem(), row.sourceLedgerYear(), row.sourceCompanyId(),
                row.journalCardId(), journalRowId, row.actionDate(),
                row.accountCode(), row.debitCredit(), row.signedAmount(),
                row.currency(), row.actionType(), row.actionId(),
                row.documentType(), row.documentNo(), row.cancelled(),
                row.dimensionSource(), row.expenseCenterId(),
                row.expenseCenterCode(), row.expenseCenterName(),
                row.expenseCenterHierarchy(), row.expenseItemId(),
                row.expenseItemName(), row.expenseCategoryId(), row.projectId(),
                row.invoiceId(), row.invoiceRowId(), row.orderId(),
                row.progressId(), row.contractId(), null);
    }
}

package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.scope.ScopeContext;
import com.example.report.workcube.BudgetPlanProviderDtos.BudgetPlanRow;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class BudgetPlanProviderServiceTest {
    @Mock CompanyOptionsService companyOptions;
    @Mock BudgetPlanProviderRepository repository;
    private BudgetPlanProviderService service;

    @BeforeEach
    void setUp() {
        service = new BudgetPlanProviderService(companyOptions, repository);
    }

    @Test
    void authorizedPageIsHashedAndCursorDoesNotDiscloseSchema() {
        ScopeContext scope = scopeFor(35L);
        allowCompany(scope, 35L);
        when(repository.find(eq(35L), eq(2026), eq(0L), eq(2)))
                .thenReturn(List.of(row(1L), row(2L)));

        var page = service.findAuthorized(scope, 35L, 2026, null, 1);

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().sourceHash()).matches("[0-9a-f]{64}");
        assertThat(page.hasMore()).isTrue();
        assertThat(new String(
                Base64.getUrlDecoder().decode(page.nextCursor()),
                StandardCharsets.UTF_8))
                .isEqualTo("2026|1");
    }

    @Test
    void companyOutsideScopeIsRejectedBeforeAnyRead() {
        ScopeContext scope = scopeFor(35L);
        allowCompany(scope, 35L);

        assertThatThrownBy(() -> service.findAuthorized(scope, 350L, 2026, null, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode())
                                .isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).find(anyLong(), anyInt(), anyLong(), anyInt());
    }

    @Test
    void fiscalYearOutsideRangeIsRejected() {
        assertThatThrownBy(() -> service.findAuthorized(scopeFor(35L), 35L, 1999, null, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void cursorFromAnotherFiscalYearIsRejected() {
        ScopeContext scope = scopeFor(35L);
        allowCompany(scope, 35L);
        String foreignCursor = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("2025|7".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.findAuthorized(scope, 35L, 2026, foreignCursor, 10))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void malformedCursorIsRejected() {
        ScopeContext scope = scopeFor(35L);
        allowCompany(scope, 35L);

        assertThatThrownBy(() -> service.findAuthorized(scope, 35L, 2026, "not-base64!!!", 10))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode())
                                .isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void limitIsClampedToTheProviderMaximum() {
        ScopeContext scope = scopeFor(35L);
        allowCompany(scope, 35L);
        when(repository.find(eq(35L), eq(2026), eq(0L), eq(2001)))
                .thenReturn(List.of());

        var page = service.findAuthorized(scope, 35L, 2026, null, 999_999);

        assertThat(page.rows()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    private void allowCompany(ScopeContext scope, long companyId) {
        when(companyOptions.findAuthorized(scope)).thenReturn(List.of(
                new CompanyOptionsRepository.CompanyOption(
                        (int) companyId, "COMP", "Company " + companyId)));
    }

    private static ScopeContext scopeFor(long companyId) {
        return new ScopeContext("planner", Set.of(companyId), Set.of(), Set.of(), false);
    }

    private static BudgetPlanRow row(long rowId) {
        return new BudgetPlanRow(
                "WORKCUBE", 35L, 2026, 9L, "2026 Opex", 1, false,
                40L, rowId, LocalDate.of(2026, 3, 15), "740.01",
                12L, 3L, null, 44200L, 5L, 7L, 2L,
                BigDecimal.ZERO, new BigDecimal("1500.00"),
                "Bakım bütçesi", null);
    }
}

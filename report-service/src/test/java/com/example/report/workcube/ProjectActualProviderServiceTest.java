package com.example.report.workcube;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.commonauth.scope.ScopeContext;
import java.math.BigDecimal;
import java.time.LocalDate;
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
class ProjectActualProviderServiceTest {
    @Mock ProjectOptionsService projectOptions;
    @Mock ProjectActualProviderRepository repository;
    private ProjectActualProviderService service;

    @BeforeEach
    void setUp() {
        service = new ProjectActualProviderService(projectOptions, repository);
    }

    @Test
    void authorizedPageIsHashedAndCursorDoesNotDisclosePhysicalSchema() {
        ScopeContext scope = new ScopeContext(
                "reader", Set.of(35L), Set.of(44200L), Set.of(), false);
        when(projectOptions.findAuthorized(scope, 35L)).thenReturn(List.of(
                new ProjectOptionsRepository.ProjectOption(
                        44200L, "44200", "IDC1", 35L, true)));
        when(repository.find(
                eq(35L), eq(44200L), any(), any(), eq(null), eq(1)))
                .thenReturn(List.of(row(1L), row(2L)));

        var page = service.findAuthorized(
                scope, 35L, 44200L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                null, 1);

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().sourceHash()).matches("[0-9a-f]{64}");
        assertThat(page.hasMore()).isTrue();
        assertThat(page.nextCursor()).isNotBlank().doesNotContain("workcube");
        assertThat(new String(
                java.util.Base64.getUrlDecoder().decode(page.nextCursor()),
                java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("2026|1")
                .doesNotContain("workcube_mikrolink");
    }

    @Test
    void projectOutsideScopeFailsBeforeAccountingRead() {
        ScopeContext scope = new ScopeContext(
                "reader", Set.of(35L), Set.of(99L), Set.of(), false);
        when(projectOptions.findAuthorized(scope, 35L)).thenReturn(List.of());

        assertThatThrownBy(() -> service.findAuthorized(
                scope, 35L, 44200L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31),
                null, 100))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(error -> assertThat(
                        ((ResponseStatusException) error).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
        verify(repository, never()).find(
                anyLong(), anyLong(), any(), any(), any(), anyInt());
    }

    @Test
    void authorizedSourceLinePageIsHashedWithIndependentCursor() {
        ScopeContext scope = new ScopeContext(
                "reader", Set.of(35L), Set.of(44200L), Set.of(), false);
        when(projectOptions.findAuthorized(scope, 35L)).thenReturn(List.of(
                new ProjectOptionsRepository.ProjectOption(
                        44200L, "44200", "IDC1", 35L, true)));
        when(repository.findSourceLines(
                eq(35L), eq(44200L), any(), any(), eq(null), eq(1)))
                .thenReturn(List.of(sourceLine(100L, 1), sourceLine(101L, 2)));

        var page = service.findAuthorizedSourceLines(
                scope, 35L, 44200L,
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                null, 1);

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().getFirst().sourceHash()).matches("[0-9a-f]{64}");
        assertThat(page.hasMore()).isTrue();
        assertThat(new String(
                java.util.Base64.getUrlDecoder().decode(page.nextCursor()),
                java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("2026|100")
                .doesNotContain("workcube_mikrolink");
    }

    private ProjectActualProviderDtos.ProjectActualRow row(long journalRowId) {
        return new ProjectActualProviderDtos.ProjectActualRow(
                "WORKCUBE",
                2026,
                35L,
                44200L,
                9000L,
                journalRowId,
                LocalDate.of(2026, 6, 1),
                "740.01",
                "DEBIT",
                new BigDecimal("100.00"),
                "TRY",
                56,
                8000L,
                null,
                "INVOICE",
                "DOC-" + journalRowId,
                "HEADER_ONLY",
                false,
                null);
    }

    private ProjectActualProviderDtos.ProjectSourceLineRow sourceLine(
            long sourceLineId,
            int ordinal) {
        return new ProjectActualProviderDtos.ProjectSourceLineRow(
                "WORKCUBE",
                2026,
                35L,
                44200L,
                8000L,
                sourceLineId,
                ordinal,
                LocalDate.of(2026, 6, 1),
                "INVOICE",
                "PURCHASE_INVOICE",
                "DOC-1",
                "Service " + ordinal,
                "Synthetic source line",
                BigDecimal.ONE,
                "EA",
                new BigDecimal("100.00"),
                new BigDecimal("100.00"),
                new BigDecimal("20.00"),
                new BigDecimal("20.00"),
                new BigDecimal("120.00"),
                "TRY",
                "740.01",
                false,
                null);
    }
}

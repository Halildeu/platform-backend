package com.example.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.transcript.security.AdminTenantContext;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.stubbing.Answer;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * CSV export cap-abort tests for {@link TranscriptExportService}.
 *
 * <p>Proves the count↔stream TOCTOU is closed: the streaming query carries a
 * {@code LIMIT cap+1} and the in-stream hard-abort refuses to emit the
 * {@code (cap+1)}-th row. A cap-exceeding export is data-fail-closed (the
 * over-cap row's payload is NEVER written — no silent truncation of a
 * privacy-sensitive dataset); a direct service caller observes the 400 signal,
 * while the authoritative clean HTTP 400 is the controller preflight gate
 * (see AdminTranscriptControllerTest). The preflight audit count is the bounded
 * count so {@code result_count} matches what is actually streamed.
 *
 * <p>Mockito-only: the {@link NamedParameterJdbcTemplate} cursor is simulated by
 * driving the {@link RowCallbackHandler} the exporter registers with N synthetic
 * rows, so we exercise the real exporter loop + abort without a database.
 */
@ExtendWith(MockitoExtension.class)
class TranscriptExportServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID MEETING = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Mock
    private NamedParameterJdbcTemplate jdbc;
    @Mock
    private TranscriptAccessAuditService accessAuditService;

    private final AdminTenantContext context = new AdminTenantContext(TENANT, "admin@example.com");

    /** exportMaxRows = 2 so cap+1 = 3 trips the guard with small fixtures. */
    private TranscriptExportService service() {
        return new TranscriptExportService(jdbc, accessAuditService, "transcript_service", 2);
    }

    // ────────────────── prepareCsv: bounded count + LIMIT ──────────────────

    @Test
    void prepareCsv_underCap_writesAuditWithBoundedCount_andBuildsLimitedQuery() {
        // Bounded preflight count returns 2 (== cap, allowed).
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);

        TranscriptExportService.CsvPlan plan = service().prepareCsv(context, MEETING);

        // Audit result_count == the bounded count (not an unbounded total).
        verify(accessAuditService).recordExport(context, MEETING, 2);
        // The streamed query is LIMIT-capped so a runaway scope can't full-scan.
        assertThat(plan.query().sql().toUpperCase()).contains("LIMIT :MAXROWS");
        assertThat(plan.columns()).isEqualTo(TranscriptExportService.COLUMNS);
        assertThat(plan.filename()).isEqualTo("transcript-" + MEETING + ".csv");
    }

    @Test
    void prepareCsv_overCap_isRejected_andNoAudit() {
        // Bounded preflight count returns cap+1 = 3 → over cap.
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(3L);

        assertThatThrownBy(() -> service().prepareCsv(context, MEETING))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));
        // No audit row is written for a refused (never-streamed) export.
        verify(accessAuditService, never()).recordExport(any(), any(), anyInt());
    }

    // ────────────────── streamCsv: in-stream hard cap-abort ─────────────────

    @Test
    void streamCsv_capPlusOneRows_failsClosed_andBodyHasNoOverCapRow() {
        TranscriptExportService svc = service();
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L); // preflight passes; the race adds a 3rd row at stream time
        TranscriptExportService.CsvPlan plan = svc.prepareCsv(context, MEETING);

        // The DB cursor now yields cap+1 = 3 rows (concurrent insert after count).
        driveHandlerWith(3);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThatThrownBy(() -> svc.streamCsv(plan, out))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.BAD_REQUEST));

        String csv = out.toString(StandardCharsets.UTF_8);
        // Header + the first `cap` rows may be in the buffer, but the over-cap
        // row (seg-2) MUST NOT be emitted — fail-closed, no cap-exceeding body.
        assertThat(csv).doesNotContain("seg-2");
    }

    @Test
    void streamCsv_atCap_streamsAllRows_noAbort() {
        TranscriptExportService svc = service();
        when(jdbc.queryForObject(anyString(), any(SqlParameterSource.class), eq(Long.class)))
                .thenReturn(2L);
        TranscriptExportService.CsvPlan plan = svc.prepareCsv(context, MEETING);

        // Exactly cap (=2) rows → streams fully, no abort.
        driveHandlerWith(2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        svc.streamCsv(plan, out);

        String csv = out.toString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Segment Id"); // header label
        assertThat(csv).contains("seg-0");
        assertThat(csv).contains("seg-1");
        assertThat(csv).doesNotContain("seg-2");
    }

    // ─────────────────────────── test plumbing ────────────────────────────

    /**
     * Make the mocked {@code jdbc.query(sql, params, RowCallbackHandler)} feed
     * the registered handler {@code rowCount} synthetic rows. Each row's
     * {@code id} column is {@code seg-<i>}; the other columns return null
     * (irrelevant to the cap-abort assertions). The handler throwing on the
     * cap+1-th row propagates exactly as a real cursor would.
     */
    private void driveHandlerWith(int rowCount) {
        Answer<Void> answer = invocation -> {
            RowCallbackHandler handler = invocation.getArgument(2);
            for (int i = 0; i < rowCount; i++) {
                ResultSet rs = org.mockito.Mockito.mock(ResultSet.class);
                final int idx = i;
                // lenient: the cap+1-th row's ResultSet is created but the
                // exporter aborts BEFORE reading it, so this stub goes unused;
                // strict-stubs would otherwise fail the (intentional) abort.
                org.mockito.Mockito.lenient().when(rs.getObject("id")).thenReturn("seg-" + idx);
                handler.processRow(rs);
            }
            return null;
        };
        doAnswer(answer).when(jdbc)
                .query(anyString(), any(SqlParameterSource.class), any(RowCallbackHandler.class));
    }
}

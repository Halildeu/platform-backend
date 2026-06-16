package com.example.commonexport;

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Streaming CSV exporter: writes a UTF-8 BOM, semicolon-separated rows
 * straight from a JDBC {@link NamedParameterJdbcTemplate} cursor to an
 * {@link OutputStream} without materialising the result set in memory.
 *
 * <p>Extracted verbatim from report-service (Codex thread 019e2cd7) into
 * {@code common-export} for reuse by endpoint-admin-service (board #1154,
 * Codex thread 019e7e35). The ONLY change from the report-service
 * original is the query type: {@code SqlBuilder.BuiltQuery} →
 * {@link ExportQuery} (same {@code sql()} + {@code params()} accessors),
 * so the produced bytes are identical.
 */
public class CsvStreamingExporter {

    private static final String SEPARATOR = ";";
    private static final byte[] UTF8_BOM = new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /**
     * Backward-compat shim: legacy flat-export call sites still pass a
     * {@code List<String>} where field == header. The canonical
     * implementation is
     * {@link #exportWithColumns(NamedParameterJdbcTemplate, ExportQuery,
     * List, OutputStream)} which honours the {@link ExportColumn#field()}
     * / {@link ExportColumn#header()} split needed by grouped + pivot
     * export.
     */
    public static void export(NamedParameterJdbcTemplate jdbc,
                               ExportQuery query,
                               List<String> columns,
                               OutputStream out) {
        List<ExportColumn> exportColumns = columns.stream()
                .map(ExportColumn::of)
                .toList();
        exportWithColumns(jdbc, query, exportColumns, out);
    }

    public static void exportWithColumns(NamedParameterJdbcTemplate jdbc,
                                          ExportQuery query,
                                          List<ExportColumn> columns,
                                          OutputStream out) {
        exportWithColumns(jdbc, query, columns, out, NO_ROW_CAP);
    }

    /**
     * Sentinel value for {@link #exportWithColumns(NamedParameterJdbcTemplate,
     * ExportQuery, List, OutputStream, long)} meaning "no hard row cap"; the
     * stream runs the query to completion (legacy behaviour).
     */
    public static final long NO_ROW_CAP = -1L;

    /**
     * Streaming CSV export with an optional <em>hard</em> row cap. When
     * {@code maxRows >= 0} the writer aborts by throwing
     * {@link ExportRowCapExceededException} the instant it is asked to write
     * the {@code (maxRows + 1)}-th data row — BEFORE that row (or any further
     * byte) is emitted — so a cap-exceeding export is <em>data</em>-fail-closed:
     * the over-cap row is never written and the result is never a
     * truncated-but-otherwise-valid CSV that silently drops rows. (Whether the
     * caller can still turn that into a clean 4xx <em>status</em> depends on
     * whether the HTTP response has already been committed; on a committed
     * streaming response the body is aborted/truncated instead — the
     * no-over-cap-row guarantee holds regardless.)
     *
     * <p>The caller is expected to have run the query with a SQL
     * {@code LIMIT maxRows + 1} so at most one extra row is ever fetched. The
     * <b>authoritative</b> over-cap gate is the caller's preflight count
     * (clean 400 before streaming); this in-stream guard is the second line of
     * defence (defense-in-depth) that catches a row count grown by a concurrent
     * insert between a preflight count and the stream (the count↔stream TOCTOU).
     * {@code maxRows == NO_ROW_CAP} disables the guard (identical to the 4-arg
     * overload).
     *
     * <p>The CSV byte contract (UTF-8 BOM, {@code ;} separator, formula-
     * injection escaping) is identical to the uncapped path — the ONLY
     * difference is the abort.
     */
    public static void exportWithColumns(NamedParameterJdbcTemplate jdbc,
                                          ExportQuery query,
                                          List<ExportColumn> columns,
                                          OutputStream out,
                                          long maxRows) {
        try {
            out.write(UTF8_BOM);
            PrintWriter writer = new PrintWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8));

            // Header row uses the human-readable label, not the SQL
            // alias. Flat export's ExportColumn.of(field) collapses
            // header == field so the legacy bytewise output stays
            // identical when the new wrapper is in use.
            StringBuilder headerLine = new StringBuilder();
            for (int i = 0; i < columns.size(); i++) {
                if (i > 0) headerLine.append(SEPARATOR);
                headerLine.append(escapeCell(columns.get(i).header()));
            }
            writer.println(headerLine);

            long[] rowCount = {0L};
            jdbc.query(query.sql(), query.params(), rs -> {
                // Hard cap: refuse to write the (maxRows + 1)-th row. Throw
                // BEFORE emitting it so the partial body never exceeds the cap.
                if (maxRows != NO_ROW_CAP && rowCount[0] >= maxRows) {
                    throw new ExportRowCapExceededException(maxRows);
                }
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < columns.size(); i++) {
                    if (i > 0) {
                        line.append(SEPARATOR);
                    }
                    Object val = rs.getObject(columns.get(i).field());
                    if (val != null) {
                        line.append(escapeCell(val.toString()));
                    }
                }
                writer.println(line);
                rowCount[0]++;
            });

            writer.flush();
        } catch (ExportRowCapExceededException e) {
            // Propagate the cap signal verbatim (do not wrap as a generic
            // "CSV export failed") so callers can map it to a 4xx fail-closed.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("CSV export failed", e);
        }
    }

    /**
     * Minimum CSV formula injection defence (Codex 019e2cd7 risk #5).
     * Excel/Calc evaluate any leading {@code =, +, -, @, \t, \r}
     * character as a formula; we prefix vulnerable values with a single
     * quote so they render as literal text. Combined with the existing
     * separator/quote/newline quoting rules.
     */
    private static String escapeCell(String raw) {
        if (raw == null) return "";
        String str = raw;
        if (!str.isEmpty()) {
            char c = str.charAt(0);
            if (c == '=' || c == '+' || c == '-' || c == '@' || c == '\t' || c == '\r') {
                str = "'" + str;
            }
        }
        // Codex 019e2cd7 post-impl Finding #5: CR inside a value must
        // be wrapped in quotes too, otherwise the formula-injection
        // single-quote prefix leaves a CR free to break the record
        // boundary. Tab is included for safety so a tab-delimited
        // consumer cannot be confused either.
        if (str.contains(SEPARATOR)
                || str.contains("\"")
                || str.contains("\n")
                || str.contains("\r")
                || str.contains("\t")) {
            str = "\"" + str.replace("\"", "\"\"") + "\"";
        }
        return str;
    }
}

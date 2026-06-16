package com.example.transcript.service;

import com.example.commonexport.CsvStreamingExporter;
import com.example.commonexport.ExportColumn;
import com.example.commonexport.ExportQuery;
import com.example.commonexport.ExportRowCapExceededException;
import com.example.transcript.security.AdminTenantContext;
import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Report-style streaming CSV export for transcript segments, reusing the shared
 * {@code common-export} {@link CsvStreamingExporter} so the produced bytes
 * follow the same CSV contract (UTF-8 BOM, semicolon-separated, formula-injection
 * defended) as the reporting + endpoint-device-grid modules.
 *
 * <p>The query is tenant-scoped with the canonical effective-org predicate AND
 * schema-qualified (the JDBC template does not inherit the JPA
 * {@code default_schema}). The schema name is injected from config and validated
 * against a strict identifier allow-list to keep it out of the interpolated SQL
 * as an injection vector.
 *
 * <p><b>Cap enforcement is two-layered with an honest contract:</b>
 * {@link #prepareCsv} runs a {@code LIMIT cap+1} preflight count and, when the
 * scope is over-cap, throws a clean {@code HTTP 400} BEFORE any byte is streamed
 * — this is the <em>authoritative</em> over-cap gate and the normal way an
 * over-cap export is refused. It also writes the KVKK m.12 EXPORT access-audit
 * row (bounded count) BEFORE the stream starts. {@link #streamCsv} then carries
 * a second {@code LIMIT cap+1} + an in-stream hard-abort that is a
 * defense-in-depth backstop for the narrow {@code preflight↔stream} window: it
 * GUARANTEES no over-cap personal-data row is ever written even if a concurrent
 * insert grows the result set after the preflight count. In that rare race the
 * HTTP 200 status line has already been committed by the streaming response, so
 * the response is aborted/truncated mid-stream (NOT a clean 400). The
 * data-protection invariant — at most {@code cap} rows are ever exported — holds
 * either way; only the over-cap <em>status code</em> differs between the
 * (authoritative) preflight path (400) and the (race-only) backstop path
 * (aborted 200).
 *
 * <p>Column order matches {@link #COLUMNS}; the {@code text_draft}/{@code
 * text_final} transcript columns ARE in the CSV (this is the export of the
 * personal data the user explicitly requested — and the access is recorded).
 */
@Service
public class TranscriptExportService {

    /**
     * Export columns: SQL alias == ResultSet key (the exporter addresses
     * {@code rs.getObject(field)}); header is the human label.
     */
    static final List<ExportColumn> COLUMNS = List.of(
            new ExportColumn("id", "Segment Id"),
            new ExportColumn("meeting_id", "Meeting Id"),
            new ExportColumn("session_id", "Session Id"),
            new ExportColumn("speaker_id", "Speaker Id"),
            new ExportColumn("start_time", "Start Time"),
            new ExportColumn("end_time", "End Time"),
            new ExportColumn("text_draft", "Text (Draft)"),
            new ExportColumn("text_final", "Text (Final)"),
            new ExportColumn("confidence", "Confidence"),
            new ExportColumn("status", "Status"),
            new ExportColumn("created_at", "Created At"),
            new ExportColumn("updated_at", "Updated At")
    );

    private final NamedParameterJdbcTemplate jdbc;
    private final TranscriptAccessAuditService accessAuditService;
    private final String schema;
    private final int exportMaxRows;

    public TranscriptExportService(
            NamedParameterJdbcTemplate jdbc,
            TranscriptAccessAuditService accessAuditService,
            @Value("${spring.jpa.properties.hibernate.default_schema:${TRANSCRIPT_DB_SCHEMA:transcript_service}}")
            String schema,
            @Value("${transcript.export.max-rows:50000}") int exportMaxRows) {
        this.jdbc = jdbc;
        this.accessAuditService = accessAuditService;
        this.schema = validateSchema(schema);
        this.exportMaxRows = exportMaxRows;
    }

    /** Everything the controller needs to stream the CSV response. */
    public record CsvPlan(ExportQuery query, List<ExportColumn> columns,
                          String filename, String contentType) {}

    /**
     * <b>Authoritative over-cap gate</b> + KVKK m.12 EXPORT audit, BOTH before
     * any byte is streamed. This is the PRIMARY enforcement of the row cap: the
     * preflight {@code count(*)} is bounded by {@code LIMIT exportMaxRows + 1},
     * and an over-cap scope is refused here with a clean {@code HTTP 400}
     * (via {@link ResponseStatusException}) — no body, no audit row, no stream.
     * Because this runs before the streaming response commits its 200 status
     * line, the normal over-cap case is a true fail-closed 400.
     *
     * <p>On the allowed path the audit {@code result_count} is the exact bounded
     * count (never the unbounded total). The streamed query (built here) also
     * carries {@code LIMIT exportMaxRows + 1}; combined with the in-stream
     * hard-abort in {@link #streamCsv}, this is a defense-in-depth backstop that
     * GUARANTEES no over-cap personal-data row is ever written even if a
     * concurrent insert grows the result set between this count and the stream.
     * That backstop fires only in the {@code preflight↔stream} race window —
     * see {@link #streamCsv} for why the race-path outcome is an aborted 200,
     * not a clean 400.
     */
    public CsvPlan prepareCsv(AdminTenantContext context, UUID meetingId) {
        UUID orgId = context.tenantId();

        // Authoritative over-cap gate: count rows in scope bounded to cap+1, so
        // the audit result_count matches the (bounded) rows we will actually
        // stream AND we refuse an over-cap scope with a clean 400 before any
        // byte / audit row — before the streaming response commits its 200.
        MapSqlParameterSource countParams = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("meetingId", meetingId)
                .addValue("maxRows", (long) exportMaxRows + 1);
        Long matched = jdbc.queryForObject(
                "SELECT count(*) FROM ("
                        + "  SELECT id FROM " + schema + ".transcript_segments "
                        + "  WHERE (org_id = :orgId OR (org_id IS NULL AND tenant_id = :orgId)) "
                        + "    AND meeting_id = :meetingId "
                        + "  LIMIT :maxRows"
                        + ") capped",
                countParams, Long.class);
        long rowCount = matched == null ? 0L : matched;
        if (rowCount > exportMaxRows) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Export exceeds the maximum of " + exportMaxRows
                            + " segments; narrow the scope.");
        }

        // KVKK m.12: record the EXPORT (bounded count only) AFTER the cap
        // check, BEFORE the stream. result_count == the rows we will stream.
        accessAuditService.recordExport(context, meetingId, (int) rowCount);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("meetingId", meetingId)
                .addValue("maxRows", (long) exportMaxRows + 1);
        // LIMIT exportMaxRows + 1: the stream fetches at most one row beyond the
        // cap so the in-stream hard-abort (streamCsv) can distinguish "exactly
        // at cap" from "over cap" deterministically, without scanning the whole
        // table on a runaway scope.
        String sql = "SELECT id, meeting_id, session_id, speaker_id, start_time, end_time, "
                + "       text_draft, text_final, confidence, status, created_at, updated_at "
                + "FROM " + schema + ".transcript_segments "
                + "WHERE (org_id = :orgId OR (org_id IS NULL AND tenant_id = :orgId)) "
                + "  AND meeting_id = :meetingId "
                + "ORDER BY start_time ASC, id ASC "
                + "LIMIT :maxRows";
        ExportQuery query = new ExportQuery(sql, params);
        String filename = "transcript-" + meetingId + ".csv";
        return new CsvPlan(query, COLUMNS, filename, "text/csv; charset=UTF-8");
    }

    /**
     * Stream the prepared CSV to {@code out} via the shared exporter, with the
     * {@code exportMaxRows} hard cap enforced in-stream as a
     * <b>defense-in-depth backstop</b> (the authoritative over-cap gate is the
     * {@link #prepareCsv} preflight, which already refused any over-cap scope
     * with a clean 400). This in-stream guard exists ONLY for the
     * {@code preflight↔stream} race: if a concurrent insert grows the result
     * set so the cursor reaches {@code exportMaxRows + 1} rows, the exporter
     * throws {@link ExportRowCapExceededException} BEFORE writing the over-cap
     * row — so the over-cap personal-data row is never emitted and the
     * data-protection invariant (at most {@code cap} rows exported) is
     * guaranteed even under the race.
     *
     * <p><b>Honest status contract:</b> we re-map the cap signal to a
     * {@link ResponseStatusException} {@code 400}, but on the streaming path the
     * HTTP 200 status line has typically already been committed by the
     * {@code StreamingResponseBody}; the container therefore aborts/truncates
     * the in-flight 200 response rather than producing a clean 400. The 400
     * re-map is still correct for non-committed callers and keeps the
     * fail-closed signal distinct from a generic 500. Either way, no
     * cap-exceeding body is ever written.
     */
    public void streamCsv(CsvPlan plan, OutputStream out) {
        try {
            CsvStreamingExporter.exportWithColumns(
                    jdbc, plan.query(), plan.columns(), out, exportMaxRows);
        } catch (ExportRowCapExceededException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Export exceeds the maximum of " + exportMaxRows
                            + " segments; narrow the scope.", e);
        }
    }

    /**
     * Schema identifier allow-list. The schema is config-supplied (not user
     * input) but it is interpolated into SQL, so we fail-closed on anything that
     * is not a plain {@code [a-zA-Z_][a-zA-Z0-9_]*} identifier.
     */
    private static String validateSchema(String schema) {
        if (schema == null || !schema.matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Invalid transcript DB schema identifier: " + schema);
        }
        return schema;
    }
}

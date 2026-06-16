package com.example.transcript.service;

import com.example.commonexport.CsvStreamingExporter;
import com.example.commonexport.ExportColumn;
import com.example.commonexport.ExportQuery;
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
 * <p>{@link #prepareCsv} runs the cap preflight (refuse over-cap, never silent
 * truncation) and writes the KVKK m.12 EXPORT access-audit row BEFORE any byte
 * is streamed; {@link #streamCsv} then streams the full result set.
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
     * Cap preflight + KVKK m.12 EXPORT audit, BOTH before any byte is streamed.
     * Refuses an over-cap export with {@code 400} (never a silent truncation).
     */
    public CsvPlan prepareCsv(AdminTenantContext context, UUID meetingId) {
        UUID orgId = context.tenantId();

        // Bounded preflight: count rows in scope; refuse over-cap before bytes.
        MapSqlParameterSource countParams = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("meetingId", meetingId);
        Long matched = jdbc.queryForObject(
                "SELECT count(*) FROM " + schema + ".transcript_segments "
                        + "WHERE (org_id = :orgId OR (org_id IS NULL AND tenant_id = :orgId)) "
                        + "  AND meeting_id = :meetingId",
                countParams, Long.class);
        long rowCount = matched == null ? 0L : matched;
        if (rowCount > exportMaxRows) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Export exceeds the maximum of " + exportMaxRows
                            + " segments; narrow the scope.");
        }

        // KVKK m.12: record the EXPORT (count only) AFTER the cap check,
        // BEFORE the stream.
        accessAuditService.recordExport(context, meetingId, (int) rowCount);

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("orgId", orgId)
                .addValue("meetingId", meetingId);
        String sql = "SELECT id, meeting_id, session_id, speaker_id, start_time, end_time, "
                + "       text_draft, text_final, confidence, status, created_at, updated_at "
                + "FROM " + schema + ".transcript_segments "
                + "WHERE (org_id = :orgId OR (org_id IS NULL AND tenant_id = :orgId)) "
                + "  AND meeting_id = :meetingId "
                + "ORDER BY start_time ASC, id ASC";
        ExportQuery query = new ExportQuery(sql, params);
        String filename = "transcript-" + meetingId + ".csv";
        return new CsvPlan(query, COLUMNS, filename, "text/csv; charset=UTF-8");
    }

    /** Stream the prepared CSV to {@code out} via the shared exporter. */
    public void streamCsv(CsvPlan plan, OutputStream out) {
        CsvStreamingExporter.exportWithColumns(jdbc, plan.query(), plan.columns(), out);
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

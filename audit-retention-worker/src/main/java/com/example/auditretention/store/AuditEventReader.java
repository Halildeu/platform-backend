package com.example.auditretention.store;

import com.example.auditretention.audit.AuditEventRecord;
import com.example.auditretention.config.AuditRetentionProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Faz 24 KVKK audit pipeline (gitops#1250) — SELECT-only reader over the
 * immutable {@code audit_event.audit_event} source (ADR-0042 D4.1/D4.2).
 *
 * <p>The contiguous-eligible-prefix scan is the key safety property: rows are
 * read {@code seq > cursor ORDER BY seq ASC} and collected only while still
 * eligible (cooled); the FIRST hot/ineligible row STOPS the batch — eligible
 * rows beyond a hot row are NOT pulled forward (no skip). This guarantees the
 * cursor only ever advances over a contiguous, fully-eligible prefix, so a
 * later-cooling lower-seq row can never be stranded out of the archive.
 */
@Component
public class AuditEventReader {

    private final JdbcTemplate jdbc;
    private final AuditRetentionProperties props;
    private final String selectSql;

    private static final RowMapper<AuditEventRecord> MAPPER = AuditEventReader::mapRow;

    public AuditEventReader(JdbcTemplate jdbc, AuditRetentionProperties props) {
        this.jdbc = jdbc;
        this.props = props;
        // Schema/table from trusted config (double-quoted in qualifiedSourceTable()).
        this.selectSql = "SELECT seq, id, tenant_id, event_type, session_id, user_id, chunk_seq, "
                + "http_status, rejection_code, retry_after_seconds, correlation_id, event_timestamp, "
                + "ingested_at, dedup_key, stream_entry_id, prev_hash, entry_hash, entry_hash_alg, "
                + "entry_hash_version FROM " + props.qualifiedSourceTable()
                + " WHERE seq > ? ORDER BY seq ASC LIMIT ?";
    }

    /**
     * Return the contiguous prefix of eligible rows with {@code seq > afterSeq}.
     * Stops (exclusive) at the first row with {@code event_timestamp >= cutoff}.
     * An empty result means the next row after the cursor is still hot.
     */
    public List<AuditEventRecord> scanContiguousEligiblePrefix(long afterSeq, Instant cutoff, int limit) {
        List<AuditEventRecord> raw = jdbc.query(selectSql, MAPPER, afterSeq, limit);
        List<AuditEventRecord> eligible = new ArrayList<>(raw.size());
        for (AuditEventRecord r : raw) {
            if (r.getEventTimestamp() == null || !r.getEventTimestamp().isBefore(cutoff)) {
                break; // first hot/ineligible row — STOP (no skip past it)
            }
            eligible.add(r);
        }
        return eligible;
    }

    /** Oldest eligible-but-not-yet-archived row age (lag), or 0 if none pending. */
    public long lagSeconds(long afterSeq, Instant cutoff, Instant now) {
        List<Instant> ts = jdbc.query(
                "SELECT event_timestamp FROM " + props.qualifiedSourceTable()
                        + " WHERE seq > ? AND event_timestamp < ? ORDER BY seq ASC LIMIT 1",
                (rs, n) -> toInstant(rs, "event_timestamp"), afterSeq, OffsetDateTime.ofInstant(cutoff, java.time.ZoneOffset.UTC));
        if (ts.isEmpty() || ts.get(0) == null) {
            return 0L;
        }
        long secs = now.getEpochSecond() - ts.get(0).getEpochSecond();
        return Math.max(0L, secs);
    }

    private static AuditEventRecord mapRow(ResultSet rs, int rowNum) throws SQLException {
        AuditEventRecord r = new AuditEventRecord();
        r.setSeq(getLong(rs, "seq"));
        Object idObj = rs.getObject("id");
        r.setId(idObj == null ? null : (java.util.UUID) idObj);
        r.setTenantId(getLong(rs, "tenant_id"));
        r.setEventType(rs.getString("event_type"));
        r.setSessionId(rs.getString("session_id"));
        r.setUserId(getLong(rs, "user_id"));
        r.setChunkSeq(getLong(rs, "chunk_seq"));
        int httpStatus = rs.getInt("http_status");
        r.setHttpStatus(rs.wasNull() ? null : httpStatus);
        r.setRejectionCode(rs.getString("rejection_code"));
        r.setRetryAfterSeconds(getLong(rs, "retry_after_seconds"));
        r.setCorrelationId(rs.getString("correlation_id"));
        r.setEventTimestamp(toInstant(rs, "event_timestamp"));
        r.setIngestedAt(toInstant(rs, "ingested_at"));
        r.setDedupKey(rs.getString("dedup_key"));
        r.setStreamEntryId(rs.getString("stream_entry_id"));
        r.setPrevHash(rs.getString("prev_hash"));
        r.setEntryHash(rs.getString("entry_hash"));
        r.setEntryHashAlg(rs.getString("entry_hash_alg"));
        int ehv = rs.getInt("entry_hash_version");
        r.setEntryHashVersion(rs.wasNull() ? null : ehv);
        return r;
    }

    private static Long getLong(ResultSet rs, String col) throws SQLException {
        long v = rs.getLong(col);
        return rs.wasNull() ? null : v;
    }

    private static Instant toInstant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime odt = rs.getObject(col, OffsetDateTime.class);
        return odt == null ? null : odt.toInstant();
    }
}

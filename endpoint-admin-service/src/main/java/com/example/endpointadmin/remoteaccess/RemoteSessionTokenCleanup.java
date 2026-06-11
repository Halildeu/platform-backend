package com.example.endpointadmin.remoteaccess;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.sql.Timestamp;

/**
 * Faz 22.6 B2.2c — TTL cleanup for {@code remote_session_token} (Codex 019eb54b edge-case #5/#10).
 * Purges only EXPIRED-by-time, non-REVOKED rows; <b>REVOKED rows are kept</b> so the revocation audit
 * trail survives for incident response (a separate archive/retention path governs those). Constructor-
 * injected (not a Spring bean) — the {@code @Scheduled} driver that calls this is disabled-by-default
 * (B2.2c.x). Pure DB op, fail-soft: a DB error returns 0 purged (the next run retries).
 *
 * <p>Runbook note: NEVER widen this to delete REVOKED rows — doing so destroys revocation forensics.
 */
public final class RemoteSessionTokenCleanup {

    private final JdbcTemplate jdbc;
    private final String table;

    public RemoteSessionTokenCleanup(JdbcTemplate jdbc, String schema) {
        this.jdbc = jdbc;
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        this.table = schema + ".remote_session_token";
    }

    /**
     * Delete rows whose token has expired by time AND is not REVOKED. REVOKED rows (incl. pre-emptive
     * revocations pinned to the retention horizon) are preserved for audit.
     *
     * @param now the cleanup clock (single trusted source)
     * @return number of rows purged (0 on a DB error — fail-soft, retried next run)
     */
    public int purgeExpired(Instant now) {
        if (now == null) {
            return 0;
        }
        try {
            return jdbc.update(
                    "DELETE FROM " + table + " WHERE expires_at < ? AND state <> 'REVOKED'",
                    Timestamp.from(now));
        } catch (DataAccessException ex) {
            return 0;
        }
    }
}

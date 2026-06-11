package com.example.endpointadmin.remoteaccess;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Faz 22.6 C-storage — the durable {@link RecordingSink} backed by the append-only WORM
 * {@code session_recording_entry} table (Flyway V65). One instance per recording chain (per session): each
 * {@link #append} INSERTs a row for {@code chainId}; the DB enforces WORM (PK {@code (chain_id, seq)} blocks
 * an overwrite, a unique {@code entry_hash} blocks a tamper-duplicate, and a BEFORE UPDATE/DELETE trigger
 * blocks any mutation). Constructor-injected {@link JdbcTemplate} (not a Spring bean) so it stays
 * disabled-by-default. <b>Fail-closed:</b> any DB error → {@link RecordingSinkException} so the
 * {@link SessionRecorder} latches unhealthy and the session is killed rather than continuing unrecorded.
 */
public final class DbRecordingSink implements RecordingSink {

    private static final String INSERT =
            " (chain_id, seq, timestamp_millis, kind, content_hash, previous_hash, entry_hash) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?)";

    private final String chainId;
    private final JdbcTemplate jdbc;
    private final String table;

    /**
     * @param chainId the recording chain id (the session id) this sink persists rows for
     * @param schema  the DB schema holding {@code session_recording_entry} (validated; qualified — the
     *                JdbcTemplate connection's search_path is not assumed, same as the DbCas store)
     */
    public DbRecordingSink(String chainId, JdbcTemplate jdbc, String schema) {
        if (chainId == null || chainId.isBlank()) {
            throw new IllegalArgumentException("chainId must be non-blank");
        }
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must be non-null");
        }
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        this.chainId = chainId;
        this.jdbc = jdbc;
        this.table = schema + ".session_recording_entry";
    }

    @Override
    public void append(SessionRecordingChain.Entry entry) throws RecordingSinkException {
        if (entry == null) {
            throw new RecordingSinkException("null recording entry");
        }
        try {
            int rows = jdbc.update("INSERT INTO " + table + INSERT,
                    chainId, entry.seq(), entry.timestampMillis(), entry.kind().name(),
                    entry.contentHash(), entry.previousHash(), entry.entryHash());
            if (rows != 1) {
                throw new RecordingSinkException("durable recording insert affected " + rows + " rows (expected 1)");
            }
        } catch (DataAccessException e) {
            // a duplicate (chain_id, seq) / entry_hash, a WORM-trigger refusal, or a connectivity error —
            // the entry did NOT durably commit → fail-closed (the recorder latches unhealthy).
            throw new RecordingSinkException("durable recording write failed", e);
        }
    }

    @Override
    public boolean isWritable() {
        try {
            Integer one = jdbc.queryForObject("SELECT 1", Integer.class);
            return Integer.valueOf(1).equals(one);
        } catch (DataAccessException e) {
            return false; // the store is unreachable → not writable (fail-closed health)
        }
    }
}

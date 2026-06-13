package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Faz 22.6 D10 post-pilot hardening (Codex 019ec29a) — the DURABLE, DB-backed {@link ApprovalGrantStore}: the
 * recorded dual-control session grant survives restart / multi-replica (replacing the process-local
 * {@link InMemoryApprovalGrantStore}, which the {@link OwnerGrantGateFactory} forbids in a production-like
 * profile). Constructor-injected {@link JdbcTemplate} (not a Spring bean) so it stays a pure adapter; the schema
 * is validated + qualified (the connection's search_path is not assumed, same as {@link com.example.endpointadmin.remoteaccess.DbRecordingSink}).
 *
 * <p><b>Authority state, NOT an authz relation (Codex):</b> the grant is short-lived, pinned to the full session
 * incarnation, with a TTL and exact capability set — a row in a DB, not an OpenFGA tuple. OpenFGA's place is the
 * can-request / can-approve authorization upstream; the recorded grant itself lives here.
 *
 * <p><b>Fail-closed:</b> {@link #granted} returns an empty set for a missing OR expired row, AND for any DB/read
 * error or a capabilities value that does not parse cleanly to known enums (an unknown token grants NOTHING —
 * never a partial set). {@link #record} refuses a null key or null/empty capabilities (a blank grant is a
 * fail-open hole), and upserts the session-incarnation row (a re-recorded approval replaces it, mirroring the
 * in-memory put). capabilities is stored as a deterministic, sorted, comma-separated list of enum names.
 */
public final class JdbcApprovalGrantStore implements ApprovalGrantStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcApprovalGrantStore.class);

    private static final String COLUMNS =
            "session_id, operator_tenant_id, operator_subject, session_start_epoch_millis,"
                    + " capabilities, expires_at_epoch_millis";
    private static final String KEY_PREDICATE =
            "session_id = ? AND operator_tenant_id = ? AND operator_subject = ? AND session_start_epoch_millis = ?";

    private final JdbcTemplate jdbc;
    private final String table;

    /**
     * @param jdbc   the JDBC template (held by reference only — no DB I/O at construction)
     * @param schema the DB schema holding {@code remote_bridge_approval_grant} (validated; the table is qualified)
     */
    public JdbcApprovalGrantStore(JdbcTemplate jdbc, String schema) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must be non-null");
        }
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        this.jdbc = jdbc;
        this.table = schema + ".remote_bridge_approval_grant";
    }

    /**
     * Refresh-time fail-fast probe (the bean calls this for an enabled DURABLE_DB broker): the durable store's
     * table must exist, so a misconfigured enabled broker refuses to start rather than failing lazily at the
     * first approval. Reads no rows ({@code WHERE false}).
     */
    public void probeAvailable() {
        jdbc.queryForList("SELECT 1 FROM " + table + " WHERE false");
    }

    @Override
    public void record(ApprovalGrantKey key, Set<RemoteSessionCapability> capabilities, long expiresAtEpochMillis) {
        Objects.requireNonNull(key, "key");
        if (capabilities == null || capabilities.isEmpty()) {
            throw new IllegalArgumentException("a grant must name at least one capability (no blank grant)");
        }
        int rows = jdbc.update("INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?)"
                        + " ON CONFLICT (session_id, operator_tenant_id, operator_subject, session_start_epoch_millis)"
                        + " DO UPDATE SET capabilities = EXCLUDED.capabilities,"
                        + " expires_at_epoch_millis = EXCLUDED.expires_at_epoch_millis, recorded_at = now()",
                key.sessionId(), key.operatorTenantId(), key.operatorSubject(), key.sessionStartEpochMillis(),
                serialize(capabilities), expiresAtEpochMillis);
        if (rows != 1) {
            // a durable approval that did not commit exactly one row is not a recorded approval (fail-closed)
            throw new IllegalStateException("durable approval-grant upsert affected " + rows + " rows (expected 1)");
        }
    }

    @Override
    public Set<RemoteSessionCapability> granted(ApprovalGrantKey key, long nowEpochMillis) {
        if (key == null) {
            return Set.of();
        }
        try {
            // expires_at_epoch_millis > now ⇒ still active; an expired row grants nothing (matches the in-memory
            // store's nowEpochMillis >= expiresAt = expired boundary)
            List<String> rows = jdbc.query("SELECT capabilities FROM " + table + " WHERE " + KEY_PREDICATE
                            + " AND expires_at_epoch_millis > ?",
                    (rs, rowNum) -> rs.getString(1),
                    key.sessionId(), key.operatorTenantId(), key.operatorSubject(), key.sessionStartEpochMillis(),
                    nowEpochMillis);
            if (rows.isEmpty()) {
                return Set.of(); // missing or expired → fail-closed
            }
            return deserialize(rows.get(0));
        } catch (RuntimeException dbError) {
            // a DB / read error NEVER grants a capability — fail-closed, never logged with the key
            log.warn("durable approval-grant read failed (fail-closed, granting nothing): {}", dbError.toString());
            return Set.of();
        }
    }

    /** Deterministic, sorted, comma-separated enum names (the DB CHECK + the store reject a blank list). */
    private static String serialize(Set<RemoteSessionCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    /** ALL-OR-NOTHING parse: an unknown / malformed token grants NOTHING (a partial grant would be fail-open). */
    private static Set<RemoteSessionCapability> deserialize(String stored) {
        if (stored == null || stored.isBlank()) {
            return Set.of();
        }
        EnumSet<RemoteSessionCapability> out = EnumSet.noneOf(RemoteSessionCapability.class);
        for (String token : stored.split(",")) {
            try {
                out.add(RemoteSessionCapability.valueOf(token.strip()));
            } catch (IllegalArgumentException unknownCapability) {
                return Set.of(); // an unrecognized capability token → grant nothing (fail-closed)
            }
        }
        return Set.copyOf(out);
    }
}

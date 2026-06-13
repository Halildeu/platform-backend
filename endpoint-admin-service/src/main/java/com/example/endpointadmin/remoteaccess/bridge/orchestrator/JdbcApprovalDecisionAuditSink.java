package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Faz 22.6 D10 post-pilot hardening #2 (Codex 019ec29a) — the durable WORM {@link ApprovalDecisionAuditSink}: each
 * dual-control approval decision (RECORDED + each denial reason) is appended to the {@code
 * remote_bridge_approval_decision_audit} WORM table (V68), so "who approved / denied what, when" survives restart
 * and cannot be mutated by the app role. Mirrors {@link JdbcApprovalGrantStore} (schema validated + qualified;
 * {@link JdbcTemplate} held by reference, no DB I/O at construction).
 *
 * <p><b>Write-MUST-throw (NOT fail-closed-to-empty):</b> unlike the grant-store READ path, this audit WRITE
 * propagates any failure — the {@link RemoteSessionApprovalRecorder} runs the grant write + this audit write in
 * ONE transaction, so a throw here rolls the grant back. A recorded grant can never exist without its audit row,
 * and a denial whose audit cannot be durably written becomes a SYSTEM failure (never a leaked policy oracle).
 */
public final class JdbcApprovalDecisionAuditSink implements ApprovalDecisionAuditSink {

    private static final String COLUMNS =
            "session_id, operator_tenant_id, operator_subject, session_start_epoch_millis,"
                    + " approver_principal, approver_tenant_id, decision, requested_capabilities,"
                    + " approved_capabilities, event_epoch_millis";

    private final JdbcTemplate jdbc;
    private final String table;

    public JdbcApprovalDecisionAuditSink(JdbcTemplate jdbc, String schema) {
        if (jdbc == null) {
            throw new IllegalArgumentException("jdbc must be non-null");
        }
        if (schema == null || !schema.matches("[a-z_][a-z0-9_]*")) {
            throw new IllegalArgumentException("invalid schema identifier: " + schema);
        }
        this.jdbc = jdbc;
        this.table = schema + ".remote_bridge_approval_decision_audit";
    }

    /** Refresh-time fail-fast probe (the bean calls it for an enabled DURABLE_WORM_DB sink). */
    public void probeAvailable() {
        jdbc.queryForList("SELECT 1 FROM " + table + " WHERE false");
    }

    @Override
    public void record(ApprovalDecisionAuditRecord record) {
        // a missing/empty requested-capabilities would fail the DB CHECK → throw → the transaction rolls back
        // (a validly-created session always carries a non-empty request, so this is defensive, not expected)
        String approved = record.approvedCapabilities().isEmpty() ? null
                : serialize(record.approvedCapabilities());
        int rows = jdbc.update("INSERT INTO " + table + " (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?)",
                record.sessionId(), record.operatorTenantId(), record.operatorSubject(),
                record.sessionStartEpochMillis(), record.approverPrincipal(), record.approverTenantId(),
                record.result().name(), serialize(record.requestedCapabilities()), approved,
                record.eventEpochMillis());
        if (rows != 1) {
            throw new IllegalStateException(
                    "durable approval-decision audit insert affected " + rows + " rows (expected 1)");
        }
    }

    /** Deterministic, sorted, comma-separated enum names (same encoding as the durable grant store). */
    private static String serialize(Set<RemoteSessionCapability> capabilities) {
        return capabilities.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}

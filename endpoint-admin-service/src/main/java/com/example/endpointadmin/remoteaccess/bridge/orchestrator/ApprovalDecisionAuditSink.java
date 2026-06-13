package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;

import java.util.Set;

/**
 * Faz 22.6 D10 approval write-path (Codex 019ebe06 follow-up; widened for post-pilot durable audit 019ec29a) —
 * the audit seam for a dual-control approval decision. The {@link RemoteSessionApprovalRecorder} reports EVERY
 * outcome (recorded + each denial) here, so the distinct decision reason is captured for audit WITHOUT being
 * leaked to the caller as an external oracle (the REST transport collapses all denials to one response).
 *
 * <p>The default is {@link LoggingApprovalDecisionAuditSink} (a structured app-log audit). The durable WORM sink
 * ({@link JdbcApprovalDecisionAuditSink}) is the post-pilot hardening: a durable record that survives restart, and
 * — under the durable grant store — commits in the SAME DB transaction as the grant, so a recorded grant can
 * never exist without its audit row.
 *
 * <p>The fields are auditable identifiers (session/operator/approver + the decision + the capability sets) —
 * NEVER a secret / token / JWT; that is the whole point of the audit (who approved what).
 */
@FunctionalInterface
public interface ApprovalDecisionAuditSink {

    void record(ApprovalDecisionAuditRecord record);

    /**
     * One dual-control approval decision, fully identified for a durable forensic record. {@code approverPrincipal}
     * / {@code approverTenantId} are absent on a pre-flow denial (e.g. a tenant mismatch); {@code approvedCapabilities}
     * is present only on a {@code RECORDED} decision.
     */
    record ApprovalDecisionAuditRecord(String sessionId,
                                       String operatorTenantId,
                                       String operatorSubject,
                                       long sessionStartEpochMillis,
                                       String approverPrincipal,
                                       String approverTenantId,
                                       RemoteSessionApprovalRecorder.Result result,
                                       Set<RemoteSessionCapability> requestedCapabilities,
                                       Set<RemoteSessionCapability> approvedCapabilities,
                                       long eventEpochMillis) {

        public ApprovalDecisionAuditRecord {
            requestedCapabilities = requestedCapabilities == null ? Set.of() : Set.copyOf(requestedCapabilities);
            approvedCapabilities = approvedCapabilities == null ? Set.of() : Set.copyOf(approvedCapabilities);
        }
    }
}

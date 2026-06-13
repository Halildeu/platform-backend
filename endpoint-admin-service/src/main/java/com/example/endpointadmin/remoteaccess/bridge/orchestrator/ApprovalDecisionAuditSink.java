package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

/**
 * Faz 22.6 D10 approval write-path (Codex 019ebe06 follow-up) — the audit seam for a dual-control approval
 * decision. The {@link RemoteSessionApprovalRecorder} reports EVERY outcome (recorded + each denial) here, so the
 * distinct decision reason is captured for audit WITHOUT being leaked to the caller as an external oracle (the
 * REST transport collapses all denials to one response).
 *
 * <p>The default is {@link LoggingApprovalDecisionAuditSink} (a structured app-log audit). A durable / WORM /
 * hash-chained sink (mirroring {@code DurableRemoteBridgeAuditSink}) is the owner-gated live slice — this seam is
 * the wiring point for it.
 *
 * <p>The fields are auditable identifiers (session id, operator subject, approver subject) — NEVER a secret /
 * token / JWT; that is the whole point of the audit (who approved what).
 */
@FunctionalInterface
public interface ApprovalDecisionAuditSink {

    void record(String sessionId, String operatorSubject, String approverPrincipal,
                RemoteSessionApprovalRecorder.Result result, long nowEpochMillis);
}

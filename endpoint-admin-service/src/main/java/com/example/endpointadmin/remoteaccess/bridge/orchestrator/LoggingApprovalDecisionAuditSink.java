package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 D10 approval write-path (Codex 019ebe06 follow-up) — the default {@link ApprovalDecisionAuditSink}: a
 * structured app-log audit. A recorded approval logs at INFO; EVERY denial logs at WARN with its distinct reason.
 * The durable / WORM / hash-chained sink is the owner-gated live slice; this is the fail-safe baseline so the
 * decision reason is never silently dropped.
 */
public final class LoggingApprovalDecisionAuditSink implements ApprovalDecisionAuditSink {

    private static final Logger log = LoggerFactory.getLogger("remote-bridge.approval-audit");

    @Override
    public void record(String sessionId, String operatorSubject, String approverPrincipal,
                       RemoteSessionApprovalRecorder.Result result, long nowEpochMillis) {
        if (result == RemoteSessionApprovalRecorder.Result.RECORDED) {
            log.info("approval RECORDED session={} operator={} approver={} at={}",
                    sessionId, operatorSubject, approverPrincipal, nowEpochMillis);
        } else {
            log.warn("approval REFUSED session={} operator={} approver={} result={} at={}",
                    sessionId, operatorSubject, approverPrincipal, result, nowEpochMillis);
        }
    }
}

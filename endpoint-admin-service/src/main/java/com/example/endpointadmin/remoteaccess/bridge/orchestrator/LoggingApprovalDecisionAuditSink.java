package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalDecisionAuditSink.ApprovalDecisionAuditRecord;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteSessionApprovalRecorder.Result;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Faz 22.6 D10 approval write-path (Codex 019ebe06 follow-up) — the default {@link ApprovalDecisionAuditSink}: a
 * structured app-log audit. A recorded approval logs at INFO; EVERY denial logs at WARN with its distinct reason.
 * The durable WORM sink ({@link JdbcApprovalDecisionAuditSink}) is the post-pilot hardening; this is the fail-safe
 * baseline so the decision reason is never silently dropped. Logs identifiers only — never a secret/token.
 */
public final class LoggingApprovalDecisionAuditSink implements ApprovalDecisionAuditSink {

    private static final Logger log = LoggerFactory.getLogger("remote-bridge.approval-audit");

    @Override
    public void record(ApprovalDecisionAuditRecord record) {
        if (record.result() == Result.RECORDED) {
            log.info("approval RECORDED session={} operator={} approver={} caps={} at={}",
                    record.sessionId(), record.operatorSubject(), record.approverPrincipal(),
                    record.approvedCapabilities(), record.eventEpochMillis());
        } else {
            log.warn("approval REFUSED session={} operator={} approver={} result={} at={}",
                    record.sessionId(), record.operatorSubject(), record.approverPrincipal(),
                    record.result(), record.eventEpochMillis());
        }
    }
}

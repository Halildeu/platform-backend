package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalFlow;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.WireContract;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalDecisionAuditSink.ApprovalDecisionAuditRecord;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.Set;

/**
 * Faz 22.6 D10 Workstream-0 slice-2 (Codex 019ebe06; durable-audit atomicity 019ec29a) — the binder that turns a
 * dual-control APPROVAL into an owner grant: it runs the E10 {@link RemoteSessionApprovalFlow} and, only on
 * {@code ALLOWED}, records the approved capabilities into the {@link ApprovalGrantStore} (which the
 * {@link ApprovalBackedOwnerTokenGate} then reads). The (owner-gated) approval REST endpoint is the deferred
 * consumer.
 *
 * <p><b>Order matters (Codex):</b> the approver-tenant guard + capability validation run BEFORE the approval
 * flow, so an invalid request never spends the approver's fatigue budget. Everything authoritative comes from
 * the SERVER-SIDE {@link RemoteBridgeSession} (subject, tenant, requested capabilities, start), never from a
 * client-supplied field; the approver's subject/tenant must come from the approver's VERIFIED identity.
 *
 * <p><b>Grant + audit atomicity (Codex 019ec29a):</b> the grant write and the audit write run inside ONE
 * {@link TransactionOperations} boundary. With the durable (DB-backed) grant store + the durable WORM audit sink,
 * both commit or both roll back — so a recorded grant can NEVER exist without its audit row, and a denial whose
 * audit cannot be durably written becomes a SYSTEM failure (the exception propagates), never a leaked policy
 * oracle. With the in-memory store + logging sink (the pilot), {@code TransactionOperations.withoutTransaction()}
 * runs the same sequence best-effort.
 */
public final class RemoteSessionApprovalRecorder {

    /** Why an approval was recorded or refused — internal audit detail, never an external oracle to the caller. */
    public enum Result {
        RECORDED,
        DENIED_TENANT_MISMATCH,
        DENIED_INVALID_CAPABILITIES,
        DENIED_APPROVAL
    }

    private final RemoteSessionApprovalFlow approvalFlow;
    private final ApprovalGrantStore store;
    private final long grantTtlMillis;
    private final ApprovalDecisionAuditSink auditSink;
    private final TransactionOperations transactionOperations;

    public RemoteSessionApprovalRecorder(RemoteSessionApprovalFlow approvalFlow, ApprovalGrantStore store,
                                         long grantTtlMillis) {
        this(approvalFlow, store, grantTtlMillis, new LoggingApprovalDecisionAuditSink());
    }

    public RemoteSessionApprovalRecorder(RemoteSessionApprovalFlow approvalFlow, ApprovalGrantStore store,
                                         long grantTtlMillis, ApprovalDecisionAuditSink auditSink) {
        this(approvalFlow, store, grantTtlMillis, auditSink, TransactionOperations.withoutTransaction());
    }

    public RemoteSessionApprovalRecorder(RemoteSessionApprovalFlow approvalFlow, ApprovalGrantStore store,
                                         long grantTtlMillis, ApprovalDecisionAuditSink auditSink,
                                         TransactionOperations transactionOperations) {
        this.approvalFlow = Objects.requireNonNull(approvalFlow, "approvalFlow");
        this.store = Objects.requireNonNull(store, "store");
        if (grantTtlMillis <= 0) {
            throw new IllegalArgumentException("grantTtlMillis must be positive");
        }
        this.grantTtlMillis = grantTtlMillis;
        this.auditSink = Objects.requireNonNull(auditSink, "auditSink");
        this.transactionOperations = Objects.requireNonNull(transactionOperations, "transactionOperations");
    }

    /**
     * Record an approval for {@code session}. {@code approverPrincipal} / {@code approverTenantId} are the
     * approver's VERIFIED identity (server-side, never client-supplied). {@code approvedCapabilities} is the
     * approver's decision (may downscope the request, but cannot widen it). The grant write + the audit write
     * are atomic (one transaction) — see the class doc.
     */
    public Result record(RemoteBridgeSession session, String approverPrincipal, String approverTenantId,
                         Set<RemoteSessionCapability> approvedCapabilities, long nowEpochMillis) {
        Objects.requireNonNull(session, "session"); // server-sourced — a null session is a programming error
        return transactionOperations.execute(status -> {
            Result result = decide(session, approverPrincipal, approverTenantId, approvedCapabilities, nowEpochMillis);
            // EVERY outcome (recorded + each denial) is audited — the distinct reason is captured for audit but
            // NEVER leaked to the caller as an external oracle (the REST transport collapses all denials). The
            // audit write is in the SAME transaction as the grant write inside decide(): a durable-audit failure
            // rolls the grant back (no grant without its audit row).
            auditSink.record(new ApprovalDecisionAuditRecord(
                    session.sessionId(), session.operatorTenantId(), session.operatorSubject(),
                    session.sessionStartEpochMillis(), approverPrincipal, approverTenantId, result,
                    session.requestedCapabilities(),
                    result == Result.RECORDED ? approvedCapabilities : Set.of(),
                    nowEpochMillis));
            return result;
        });
    }

    private Result decide(RemoteBridgeSession session, String approverPrincipal, String approverTenantId,
                          Set<RemoteSessionCapability> approvedCapabilities, long nowEpochMillis) {
        // (1) approver-tenant guard: the approver must be in the SAME tenant as the session's operator. The E10
        // canonical identity is NOT a tenancy boundary — that boundary lives here (#612: cross-tenant fail-open).
        if (approverTenantId == null || approverTenantId.isBlank()
                || !approverTenantId.equals(session.operatorTenantId())) {
            return Result.DENIED_TENANT_MISMATCH;
        }

        // (2) capability validation BEFORE the approval flow → an invalid request never spends the approver's
        // fatigue budget. Approved caps must be present, all pilot, and a subset of what the session requested.
        if (approvedCapabilities == null || approvedCapabilities.isEmpty()
                || !approvedCapabilities.stream().allMatch(WireContract::isPilotCapability)
                || !session.requestedCapabilities().containsAll(approvedCapabilities)) {
            return Result.DENIED_INVALID_CAPABILITIES;
        }

        // (3) E10 dual-control approval on the TENANT-SCOPED resource (the requester is the session's operator,
        // taken server-side; the flow canonicalizes for self-approval + fatigue internally)
        String resourceId = ApprovalResourceIds.remoteSession(session.operatorTenantId(), session.sessionId());
        RemoteSessionApprovalGate.Outcome outcome = approvalFlow.decide(
                session.operatorSubject(), approverPrincipal, resourceId, nowEpochMillis);
        if (outcome != RemoteSessionApprovalGate.Outcome.ALLOWED) {
            return Result.DENIED_APPROVAL;
        }

        // (4) record the grant — keyed on the RAW session subject (matches the read path) + tenant + incarnation,
        // with a bounded TTL so a stale grant cannot outlive the session or leak to a reused sessionId
        ApprovalGrantStore.ApprovalGrantKey key = new ApprovalGrantStore.ApprovalGrantKey(
                session.sessionId(), session.operatorTenantId(), session.operatorSubject(),
                session.sessionStartEpochMillis());
        store.record(key, Set.copyOf(approvedCapabilities), nowEpochMillis + grantTtlMillis);
        return Result.RECORDED;
    }
}

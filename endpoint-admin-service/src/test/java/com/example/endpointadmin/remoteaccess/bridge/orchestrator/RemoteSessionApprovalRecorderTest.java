package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.ApprovalFatigueLimiter;
import com.example.endpointadmin.remoteaccess.InMemoryAuthzGrantResolver;
import com.example.endpointadmin.remoteaccess.InMemoryAuthzGrantResolver.Grant;
import com.example.endpointadmin.remoteaccess.InMemoryCanonicalIdentityResolver;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalFlow;
import com.example.endpointadmin.remoteaccess.RemoteSessionApprovalGate;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate.OwnerGrantContext;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.RemoteSessionApprovalRecorder.Result;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 slice-2 (Codex 019ebe06) — the binder runs the E10 dual-control approval and records the grant
 * ONLY on ALLOWED, keyed on the RAW session subject + tenant + incarnation. Cross-tenant, alias self-approval,
 * invalid capabilities (which never spend fatigue), and reused-id all fail closed.
 */
class RemoteSessionApprovalRecorderTest {

    private static final String SID = "s1";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_TENANT = "22222222-2222-2222-2222-222222222222";
    private static final String OPERATOR = "operator@x";
    private static final String OPERATOR_ALIAS = "operator-alias";
    private static final String APPROVER = "approver@y";
    private static final String UNGRANTED = "ungranted@z";
    private static final long START = 10_000L;
    private static final long NOW = 1_000L;
    private static final long TTL = 60_000L;
    private static final long WINDOW = 60_000L;
    private static final String RESOURCE = ApprovalResourceIds.remoteSession(TENANT, SID);

    private static RemoteBridgeSession session(Set<RemoteSessionCapability> requested) {
        return new RemoteBridgeSession(SID, "peer-1", "dev-1", OPERATOR, TENANT, "Operator X",
                requested, START + TTL, START, State.ACTIVE);
    }

    private static RemoteBridgeSession session() {
        return session(Set.of(RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY));
    }

    private static RemoteSessionApprovalFlow flow(ApprovalFatigueLimiter fatigue) {
        // operator/approver distinct; operator-alias canonicalizes to operator (self-approval test); ungranted is
        // resolvable but holds no grant. Grants are on the TENANT-SCOPED resource only.
        var identity = new InMemoryCanonicalIdentityResolver(Map.of(
                OPERATOR, OPERATOR, APPROVER, APPROVER, OPERATOR_ALIAS, OPERATOR, UNGRANTED, UNGRANTED));
        var gate = new RemoteSessionApprovalGate(identity, fatigue);
        var grants = new InMemoryAuthzGrantResolver(
                Set.of(new Grant(OPERATOR, RESOURCE)),
                Set.of(new Grant(APPROVER, RESOURCE), new Grant(OPERATOR_ALIAS, RESOURCE)));
        return new RemoteSessionApprovalFlow(grants, gate);
    }

    private static RemoteSessionApprovalRecorder recorder(ApprovalGrantStore store, ApprovalFatigueLimiter fatigue) {
        return new RemoteSessionApprovalRecorder(flow(fatigue), store, TTL);
    }

    @Test
    void anAllowedApprovalRecordsTheGrantAndTheGateReadsIt() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        Result r = recorder(store, new ApprovalFatigueLimiter(5, WINDOW))
                .record(session(), APPROVER, TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        assertEquals(Result.RECORDED, r);

        OwnerTokenGate gate = new ApprovalBackedOwnerTokenGate(store);
        OwnerGrantContext ctx = new OwnerGrantContext(SID, TENANT, OPERATOR, START);
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY), gate.grantedCapabilities(ctx, NOW + 1));
        // the grant is TTL-bounded — it expires
        assertTrue(gate.grantedCapabilities(ctx, NOW + TTL).isEmpty());
    }

    @Test
    void aDeniedApprovalRecordsNothing() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        // ungranted approver holds no can_approve grant → E10 DENIED_MISSING_GRANT → no record
        Result r = recorder(store, new ApprovalFatigueLimiter(5, WINDOW))
                .record(session(), UNGRANTED, TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        assertEquals(Result.DENIED_APPROVAL, r);
        assertTrue(new ApprovalBackedOwnerTokenGate(store)
                .grantedCapabilities(new OwnerGrantContext(SID, TENANT, OPERATOR, START), NOW + 1).isEmpty());
    }

    @Test
    void anAliasSelfApprovalRecordsNothing() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        // approver = operator-alias which canonicalizes to the operator → self-approval → no record
        Result r = recorder(store, new ApprovalFatigueLimiter(5, WINDOW))
                .record(session(), OPERATOR_ALIAS, TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        assertEquals(Result.DENIED_APPROVAL, r);
        assertTrue(new ApprovalBackedOwnerTokenGate(store)
                .grantedCapabilities(new OwnerGrantContext(SID, TENANT, OPERATOR, START), NOW + 1).isEmpty());
    }

    @Test
    void aTenantMismatchIsRefusedBeforeTheApproval() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        // approver's tenant ≠ the session's operator tenant → refused before the approval flow (cross-tenant)
        Result r = recorder(store, new ApprovalFatigueLimiter(5, WINDOW))
                .record(session(), APPROVER, OTHER_TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        assertEquals(Result.DENIED_TENANT_MISMATCH, r);
        assertTrue(new ApprovalBackedOwnerTokenGate(store)
                .grantedCapabilities(new OwnerGrantContext(SID, TENANT, OPERATOR, START), NOW + 1).isEmpty());
    }

    @Test
    void invalidCapabilitiesAreRefusedAndSpendNoFatigue() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        RemoteSessionApprovalRecorder rec = recorder(store, new ApprovalFatigueLimiter(1, WINDOW)); // cap 1
        // empty / non-pilot / not-a-subset → all DENIED_INVALID_CAPABILITIES, none reaches the approval flow
        assertEquals(Result.DENIED_INVALID_CAPABILITIES, rec.record(session(), APPROVER, TENANT, Set.of(), NOW));
        assertEquals(Result.DENIED_INVALID_CAPABILITIES,
                rec.record(session(), APPROVER, TENANT, Set.of(RemoteSessionCapability.FULL_RDP), NOW));
        assertEquals(Result.DENIED_INVALID_CAPABILITIES, rec.record(session(Set.of(RemoteSessionCapability.VIEW_ONLY)),
                APPROVER, TENANT, Set.of(RemoteSessionCapability.CONSTRAINED_PTY), NOW)); // not requested
        // the approver spent no fatigue on the invalid attempts → a valid approval still succeeds (cap was 1)
        assertEquals(Result.RECORDED,
                rec.record(session(), APPROVER, TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW));
    }

    @Test
    void theGrantIsKeyedOnTheRawSubjectTenantAndIncarnation() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        recorder(store, new ApprovalFatigueLimiter(5, WINDOW))
                .record(session(), APPROVER, TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        OwnerTokenGate gate = new ApprovalBackedOwnerTokenGate(store);
        // exact incarnation → grant
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY),
                gate.grantedCapabilities(new OwnerGrantContext(SID, TENANT, OPERATOR, START), NOW + 1));
        // different tenant → empty (cross-tenant)
        assertTrue(gate.grantedCapabilities(new OwnerGrantContext(SID, OTHER_TENANT, OPERATOR, START), NOW + 1).isEmpty());
        // different sessionStart (reused id, new incarnation) → empty
        assertTrue(gate.grantedCapabilities(new OwnerGrantContext(SID, TENANT, OPERATOR, START + 1), NOW + 1).isEmpty());
        // the alias-canonical subject does NOT read the grant — keying is on the RAW subject (locks decision A)
        assertTrue(gate.grantedCapabilities(new OwnerGrantContext(SID, TENANT, OPERATOR_ALIAS, START), NOW + 1).isEmpty());
    }

    @Test
    void everyOutcomeIsAuditedWithItsDistinctReason() {
        java.util.List<String> audited = new java.util.ArrayList<>();
        ApprovalDecisionAuditSink sink = r ->
                audited.add(r.sessionId() + ":" + r.operatorSubject() + ":" + r.approverPrincipal() + ":" + r.result());
        RemoteSessionApprovalRecorder rec = new RemoteSessionApprovalRecorder(
                flow(new ApprovalFatigueLimiter(5, WINDOW)), new InMemoryApprovalGrantStore(), TTL, sink);
        // a valid approval → RECORDED audited; a tenant mismatch → DENIED_TENANT_MISMATCH audited (distinct reason)
        rec.record(session(), APPROVER, TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        rec.record(session(), APPROVER, OTHER_TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW);
        assertEquals(java.util.List.of(
                SID + ":" + OPERATOR + ":" + APPROVER + ":RECORDED",
                SID + ":" + OPERATOR + ":" + APPROVER + ":DENIED_TENANT_MISMATCH"), audited);
    }

    @Test
    void aThrowingAuditSinkPropagates() {
        // grant + audit run in one transaction boundary — a failing audit must NOT be swallowed; it propagates so
        // the caller's transaction rolls back (the durable grant+audit atomicity is proven in the PG IT)
        ApprovalDecisionAuditSink throwing = r -> {
            throw new IllegalStateException("durable audit down");
        };
        RemoteSessionApprovalRecorder rec = new RemoteSessionApprovalRecorder(
                flow(new ApprovalFatigueLimiter(5, WINDOW)), new InMemoryApprovalGrantStore(), TTL, throwing);
        assertThrows(IllegalStateException.class,
                () -> rec.record(session(), APPROVER, TENANT, Set.of(RemoteSessionCapability.VIEW_ONLY), NOW));
    }

    @Test
    void nullCollaboratorsRejectedAtConstruction() {
        ApprovalGrantStore store = new InMemoryApprovalGrantStore();
        RemoteSessionApprovalFlow f = flow(new ApprovalFatigueLimiter(1, WINDOW));
        assertThrows(NullPointerException.class, () -> new RemoteSessionApprovalRecorder(null, store, TTL));
        assertThrows(NullPointerException.class, () -> new RemoteSessionApprovalRecorder(f, null, TTL));
        assertThrows(IllegalArgumentException.class, () -> new RemoteSessionApprovalRecorder(f, store, 0));
    }
}

package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerGrantGateFactory.GateType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 D10 slice-2 (Codex 019ebe06) — the owner-grant gate factory matrix: DENY_ALL is the default (even in
 * prod), APPROVAL_BACKED_IN_MEMORY builds the real gate outside prod but is forbidden in a prod-like profile and
 * requires a store.
 */
class OwnerGrantGateFactoryTest {

    @Test
    void denyAllIsTheDefaultAndAllowedEverywhere() {
        assertSame(OwnerTokenGate.DENY_ALL, OwnerGrantGateFactory.create(GateType.DENY_ALL, null, false));
        assertSame(OwnerTokenGate.DENY_ALL, OwnerGrantGateFactory.create(GateType.DENY_ALL, null, true));
    }

    @Test
    void aNullTypeDefaultsToDenyAll() {
        assertSame(OwnerTokenGate.DENY_ALL, OwnerGrantGateFactory.create(null, null, true));
    }

    @Test
    void approvalBackedBuildsTheRealGateOutsideProd() {
        assertInstanceOf(ApprovalBackedOwnerTokenGate.class, OwnerGrantGateFactory.create(
                GateType.APPROVAL_BACKED_IN_MEMORY, new InMemoryApprovalGrantStore(), false));
    }

    @Test
    void approvalBackedIsForbiddenInAProdLikeProfile() {
        assertThrows(IllegalStateException.class, () -> OwnerGrantGateFactory.create(
                GateType.APPROVAL_BACKED_IN_MEMORY, new InMemoryApprovalGrantStore(), true));
    }

    @Test
    void approvalBackedRequiresAStore() {
        assertThrows(IllegalStateException.class, () -> OwnerGrantGateFactory.create(
                GateType.APPROVAL_BACKED_IN_MEMORY, null, false));
    }

    @Test
    void durableDbBuildsTheRealGateInEveryProfile() {
        // the durable DB-backed store survives restart → allowed in a prod-like profile (unlike the in-memory one).
        // The factory is store-IMPL-agnostic (it wraps any non-null store), so an in-memory double is enough here;
        // the CONCRETE JdbcApprovalGrantStore wiring is proven by RemoteBridgeServerConfig + the durable PG IT.
        assertInstanceOf(ApprovalBackedOwnerTokenGate.class, OwnerGrantGateFactory.create(
                GateType.APPROVAL_BACKED_DURABLE_DB, new InMemoryApprovalGrantStore(), false));
        assertInstanceOf(ApprovalBackedOwnerTokenGate.class, OwnerGrantGateFactory.create(
                GateType.APPROVAL_BACKED_DURABLE_DB, new InMemoryApprovalGrantStore(), true));
    }

    @Test
    void durableDbRequiresAStore() {
        assertThrows(IllegalStateException.class, () -> OwnerGrantGateFactory.create(
                GateType.APPROVAL_BACKED_DURABLE_DB, null, false));
        assertThrows(IllegalStateException.class, () -> OwnerGrantGateFactory.create(
                GateType.APPROVAL_BACKED_DURABLE_DB, null, true));
    }
}

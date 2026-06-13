package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore.ApprovalGrantKey;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.OwnerTokenGate.OwnerGrantContext;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 slice-2 (Codex 019ebe06) — the approval-backed gate reads the store for the context's exact
 * incarnation, fail-closed: an ill-formed context (null / blank field) returns empty WITHOUT throwing, and an
 * expired grant is empty.
 */
class ApprovalBackedOwnerTokenGateTest {

    private static final String SID = "s1";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "operator@x";
    private static final long START = 10_000L;

    private static OwnerGrantContext ctx() {
        return new OwnerGrantContext(SID, TENANT, SUBJECT, START);
    }

    @Test
    void readsTheStoredGrant() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        store.record(new ApprovalGrantKey(SID, TENANT, SUBJECT, START), Set.of(RemoteSessionCapability.VIEW_ONLY),
                999_999L);
        OwnerTokenGate gate = new ApprovalBackedOwnerTokenGate(store);
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY), gate.grantedCapabilities(ctx(), 1_000L));
    }

    @Test
    void anExpiredGrantIsEmpty() {
        InMemoryApprovalGrantStore store = new InMemoryApprovalGrantStore();
        store.record(new ApprovalGrantKey(SID, TENANT, SUBJECT, START), Set.of(RemoteSessionCapability.VIEW_ONLY),
                5_000L);
        assertTrue(new ApprovalBackedOwnerTokenGate(store).grantedCapabilities(ctx(), 5_000L).isEmpty());
    }

    @Test
    void anIllFormedContextIsEmptyAndDoesNotThrow() {
        OwnerTokenGate gate = new ApprovalBackedOwnerTokenGate(new InMemoryApprovalGrantStore());
        assertTrue(gate.grantedCapabilities(null, 1_000L).isEmpty());
        assertTrue(gate.grantedCapabilities(new OwnerGrantContext("  ", TENANT, SUBJECT, START), 1_000L).isEmpty());
        assertTrue(gate.grantedCapabilities(new OwnerGrantContext(SID, "  ", SUBJECT, START), 1_000L).isEmpty());
        assertTrue(gate.grantedCapabilities(new OwnerGrantContext(SID, TENANT, "  ", START), 1_000L).isEmpty());
    }

    @Test
    void nullStoreRejectedAtConstruction() {
        assertThrows(NullPointerException.class, () -> new ApprovalBackedOwnerTokenGate(null));
    }
}

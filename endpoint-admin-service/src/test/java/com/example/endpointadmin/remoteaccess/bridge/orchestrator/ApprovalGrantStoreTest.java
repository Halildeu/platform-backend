package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.orchestrator.ApprovalGrantStore.ApprovalGrantKey;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D10 slice-2 (Codex 019ebe06) — the in-memory grant store is expiry-aware + fail-closed: a recorded
 * grant reads back until it expires; a missing/expired key reads empty; the key requires the full
 * (sessionId, tenant, subject, sessionStart) incarnation so a different tenant / reused id cannot match.
 */
class ApprovalGrantStoreTest {

    private static final String SID = "s1";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String SUBJECT = "operator@x";
    private static final long START = 10_000L;

    private static ApprovalGrantKey key() {
        return new ApprovalGrantKey(SID, TENANT, SUBJECT, START);
    }

    @Test
    void aRecordedGrantReadsBackUntilItExpires() {
        ApprovalGrantStore store = new InMemoryApprovalGrantStore();
        store.record(key(), Set.of(RemoteSessionCapability.VIEW_ONLY), 5_000L); // expires at 5000
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY), store.granted(key(), 4_999L));
        // at/after expiry → empty (fail-closed)
        assertTrue(store.granted(key(), 5_000L).isEmpty());
        assertTrue(store.granted(key(), 6_000L).isEmpty());
    }

    @Test
    void aMissingKeyReadsEmpty() {
        assertTrue(new InMemoryApprovalGrantStore().granted(key(), 1_000L).isEmpty());
        assertTrue(new InMemoryApprovalGrantStore().granted(null, 1_000L).isEmpty());
    }

    @Test
    void aDifferentTenantOrSubjectOrIncarnationDoesNotMatch() {
        ApprovalGrantStore store = new InMemoryApprovalGrantStore();
        store.record(key(), Set.of(RemoteSessionCapability.VIEW_ONLY), 999_999L);
        // same sessionId+subject+start but a DIFFERENT tenant → no grant (cross-tenant fail-open closed)
        assertTrue(store.granted(new ApprovalGrantKey(SID, "22222222-2222-2222-2222-222222222222", SUBJECT, START),
                1_000L).isEmpty());
        // same sessionId+tenant+subject but a DIFFERENT start (a reused id, new incarnation) → no grant
        assertTrue(store.granted(new ApprovalGrantKey(SID, TENANT, SUBJECT, START + 1), 1_000L).isEmpty());
        // a different subject → no grant
        assertTrue(store.granted(new ApprovalGrantKey(SID, TENANT, "other@x", START), 1_000L).isEmpty());
    }

    @Test
    void recordRefusesNullKeyOrEmptyCapabilities() {
        ApprovalGrantStore store = new InMemoryApprovalGrantStore();
        assertThrows(NullPointerException.class, () -> store.record(null, Set.of(RemoteSessionCapability.VIEW_ONLY), 1L));
        assertThrows(IllegalArgumentException.class, () -> store.record(key(), Set.of(), 1L));
        assertThrows(IllegalArgumentException.class, () -> store.record(key(), null, 1L));
    }

    @Test
    void theKeyRequiresNonBlankFields() {
        assertThrows(IllegalArgumentException.class, () -> new ApprovalGrantKey("  ", TENANT, SUBJECT, START));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalGrantKey(SID, "  ", SUBJECT, START));
        assertThrows(IllegalArgumentException.class, () -> new ApprovalGrantKey(SID, TENANT, null, START));
    }
}

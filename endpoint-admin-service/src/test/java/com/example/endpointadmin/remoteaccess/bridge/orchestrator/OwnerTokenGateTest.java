package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 T-4a-ii slice-3 (Codex 019ebbfa) — the owner-token gate is the authoritative grant source with a
 * fail-closed DENY-ALL default; the request only narrows, the pilot allowlist bounds.
 */
class OwnerTokenGateTest {

    @Test
    void denyAllGrantsNothing() {
        assertTrue(OwnerTokenGate.DENY_ALL.grantedCapabilities("sess-1", "operator@x").isEmpty());
    }

    @Test
    void effectiveGrantIsOwnerAuthoritativeRequestNarrows() {
        // owner granted VIEW_ONLY + CONSTRAINED_PTY; the operator requested only VIEW_ONLY → effective VIEW_ONLY
        Set<RemoteSessionCapability> owner = Set.of(
                RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY);
        Set<RemoteSessionCapability> requested = Set.of(RemoteSessionCapability.VIEW_ONLY);
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY),
                OwnerTokenGate.effectiveGrant(owner, requested));
    }

    @Test
    void aRequestCannotWidenBeyondTheOwnerGrant() {
        // owner granted only VIEW_ONLY; the operator requests CONSTRAINED_PTY too → it is NOT granted
        Set<RemoteSessionCapability> owner = Set.of(RemoteSessionCapability.VIEW_ONLY);
        Set<RemoteSessionCapability> requested = Set.of(
                RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.CONSTRAINED_PTY);
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY),
                OwnerTokenGate.effectiveGrant(owner, requested));
    }

    @Test
    void aTokenCannotConferANonPilotCapability() {
        // even if a token somehow granted FULL_RDP (non-pilot), the pilot allowlist strips it
        Set<RemoteSessionCapability> owner = Set.of(
                RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.FULL_RDP);
        Set<RemoteSessionCapability> requested = Set.of(
                RemoteSessionCapability.VIEW_ONLY, RemoteSessionCapability.FULL_RDP);
        assertEquals(Set.of(RemoteSessionCapability.VIEW_ONLY),
                OwnerTokenGate.effectiveGrant(owner, requested));
    }

    @Test
    void denyAllThroughEffectiveGrantYieldsNothing() {
        // the default gate's empty grant ∩ anything = empty → every operation denied
        assertTrue(OwnerTokenGate.effectiveGrant(
                OwnerTokenGate.DENY_ALL.grantedCapabilities("s", "o"),
                Set.of(RemoteSessionCapability.VIEW_ONLY)).isEmpty());
    }

    @Test
    void nullsAreFailClosed() {
        assertTrue(OwnerTokenGate.effectiveGrant(null, Set.of(RemoteSessionCapability.VIEW_ONLY)).isEmpty());
        assertTrue(OwnerTokenGate.effectiveGrant(Set.of(RemoteSessionCapability.VIEW_ONLY), null).isEmpty());
    }
}

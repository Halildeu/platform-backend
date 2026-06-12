package com.example.endpointadmin.remoteaccess.bridge.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 T-1a — {@link CanonicalCommand} canonicalisation + order/content-sensitive hash. */
class CanonicalCommandTest {

    @Test
    void canonicalisesLikeTheD2D3Tokeniser() {
        CanonicalCommand c = CanonicalCommand.of("IpConfig /all");
        assertEquals("ipconfig", c.commandId());          // command id is lowercased
        assertEquals(List.of("/all"), c.argv());
        assertEquals(List.of("a", "b"), CanonicalCommand.of("ping   a   b").argv()); // collapsed spaces
    }

    @Test
    void theHashIsStableForTheSameCommand() {
        assertEquals(CanonicalCommand.of("ping -n 4 10.0.0.1").hash(),
                CanonicalCommand.of("ping -n 4 10.0.0.1").hash());
    }

    @Test
    void theHashChangesWhenArgumentOrderOrContentChanges() {
        assertNotEquals(CanonicalCommand.of("ping -n 4").hash(), CanonicalCommand.of("ping 4 -n").hash()); // order
        assertNotEquals(CanonicalCommand.of("ping -n 4").hash(), CanonicalCommand.of("ping -n 5").hash()); // content
        assertNotEquals(CanonicalCommand.of("ping -n 4").hash(), CanonicalCommand.of("ping -n 4 x").hash()); // extra arg
        // delimiter-safe: "a b" (two tokens) must not collide with "ab" (one token)
        assertNotEquals(CanonicalCommand.of("cmd a b").hash(), CanonicalCommand.of("cmd ab").hash());
    }

    @Test
    void aBlankOrNullCommandIsTheEmptyCanonicalisation() {
        assertTrue(CanonicalCommand.of(null).isEmpty());
        assertTrue(CanonicalCommand.of("   ").isEmpty());
        assertEquals(CanonicalCommand.of(null).hash(), CanonicalCommand.of("").hash()); // stable empty hash
    }

    @Test
    void theDirectConstructorEnforcesTheSameCanonicalInvariantAsOf() {
        // a direct construction must be indistinguishable from of() — the ctor lowercases commandId (Codex)
        assertEquals(CanonicalCommand.of("IpConfig /all").hash(),
                new CanonicalCommand("IpConfig", java.util.List.of("/all")).hash());
        assertEquals("ipconfig", new CanonicalCommand("IPCONFIG", java.util.List.of()).commandId());
    }

    @Test
    void aNullArgvElementIsRejectedAtConstructionNotLaterInHash() {
        assertThrows(NullPointerException.class,
                () -> new CanonicalCommand("cmd", java.util.Arrays.asList("/a", null)));
    }
}

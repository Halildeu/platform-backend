package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.ConstrainedPtyGate.Result;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D-3 — {@link ConstrainedPtyGate} composition: proves D-2 (command guard) and D-3 (argument policy)
 * act as ONE transitive control, refusing at the right layer (Codex 019eb874 HIGH finding).
 */
class ConstrainedPtyGateTest {

    private final ConstrainedPtyGate gate = ConstrainedPtyGate.PILOT;

    @Test
    void aSafeAllowlistedInPolicyCommandPasturesBothGates() {
        Result r = gate.evaluate("ping -n 4 10.0.0.1");
        assertTrue(r.permitted());
        assertEquals(PtyCommandGuard.Decision.ALLOWED, r.command());
        assertEquals(PtyArgumentPolicy.Decision.ALLOWED, r.argument());
        assertTrue(gate.permits("hostname"));
        assertTrue(gate.permits("tracert -d -h 30 example.com"));
    }

    @Test
    void aSyntaxOrAllowlistRefusalStopsAtTheCommandGateAndDoesNotConsultArguments() {
        // shell injection -> D-2 refuses; D-3 never consulted (argument == null)
        Result injected = gate.evaluate("ping 10.0.0.1; rm -rf /");
        assertFalse(injected.permitted());
        assertEquals(PtyCommandGuard.Decision.DENIED_UNSAFE_SYNTAX, injected.command());
        assertNull(injected.argument());

        // not allowlisted -> D-2 refuses; D-3 never consulted
        Result notAllowed = gate.evaluate("ipconfig /all");
        assertFalse(notAllowed.permitted());
        assertEquals(PtyCommandGuard.Decision.DENIED_NOT_ALLOWLISTED, notAllowed.command());
        assertNull(notAllowed.argument());
    }

    @Test
    void anAllowlistedCommandWithABadArgumentIsCaughtByTheArgumentGate() {
        // D-2 passes (ping is allowlisted, syntax safe) but D-3 catches what D-2 structurally cannot:
        Result forbidden = gate.evaluate("ping -t 10.0.0.1");          // infinite ping
        assertFalse(forbidden.permitted());
        assertEquals(PtyCommandGuard.Decision.ALLOWED, forbidden.command());
        assertEquals(PtyArgumentPolicy.Decision.DENIED_FORBIDDEN_FLAG, forbidden.argument());

        Result outOfRange = gate.evaluate("ping -n 99999 10.0.0.1");
        assertFalse(outOfRange.permitted());
        assertEquals(PtyCommandGuard.Decision.ALLOWED, outOfRange.command());
        assertEquals(PtyArgumentPolicy.Decision.DENIED_DISALLOWED_VALUE, outOfRange.argument());

        Result missingHost = gate.evaluate("ping -n 4");
        assertFalse(missingHost.permitted());
        assertEquals(PtyArgumentPolicy.Decision.DENIED_DISALLOWED_OPERAND, missingHost.argument());
    }

    @Test
    void nullsAreFailClosed() {
        assertFalse(gate.permits(null));
        assertFalse(gate.permits(""));
    }
}

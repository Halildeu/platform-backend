package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.PtyArgumentPolicy.CommandSpec;
import com.example.endpointadmin.remoteaccess.PtyArgumentPolicy.Decision;
import com.example.endpointadmin.remoteaccess.PtyArgumentPolicy.OperandRule;
import com.example.endpointadmin.remoteaccess.PtyArgumentPolicy.ValueSpec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Faz 22.6 D-3 — {@link PtyArgumentPolicy} constrained-PTY per-argument flag policy. */
class PtyArgumentPolicyTest {

    private final PtyArgumentPolicy pilot = PtyArgumentPolicy.PILOT_DEFAULT_POLICY;

    @Test
    void inPolicyCommandsWithAllowedFlagsAndOperandsAreAllowed() {
        assertEquals(Decision.ALLOWED, pilot.decide("hostname"));
        assertEquals(Decision.ALLOWED, pilot.decide("ver"));
        assertEquals(Decision.ALLOWED, pilot.decide("whoami /all"));
        assertEquals(Decision.ALLOWED, pilot.decide("whoami /groups /priv"));
        assertEquals(Decision.ALLOWED, pilot.decide("whoami /fo csv"));
        assertEquals(Decision.ALLOWED, pilot.decide("netstat -a -n -o"));
        assertEquals(Decision.ALLOWED, pilot.decide("netstat -p tcp"));
        assertEquals(Decision.ALLOWED, pilot.decide("ping example.com"));
        assertEquals(Decision.ALLOWED, pilot.decide("ping -n 4 10.0.0.1"));
        assertEquals(Decision.ALLOWED, pilot.decide("ping -n 4 -w 1000 -l 32 2001:db8::1"));
        assertEquals(Decision.ALLOWED, pilot.decide("ping 10.0.0.1 -n 4"));   // operand-before-flag, order-independent
        assertEquals(Decision.ALLOWED, pilot.decide("tracert example.com"));
        assertEquals(Decision.ALLOWED, pilot.decide("tracert -d -h 30 example.com"));
    }

    @Test
    void matchingIsCaseInsensitiveForCommandsAndFlags() {
        assertEquals(Decision.ALLOWED, pilot.decide("PING -N 4 EXAMPLE.com"));
        assertEquals(Decision.ALLOWED, pilot.decide("WhoAmI /ALL"));
        assertEquals(Decision.ALLOWED, pilot.decide("netstat -P TCP"));
    }

    @Test
    void anExplicitlyForbiddenFlagIsRefusedAndDistinct() {
        // ping -t runs forever — Codex D-2 follow-up; a distinct outcome the caller meters as a probe
        assertEquals(Decision.DENIED_FORBIDDEN_FLAG, pilot.decide("ping -t example.com"));
        assertEquals(Decision.DENIED_FORBIDDEN_FLAG, pilot.decide("ping -T 10.0.0.1"));
    }

    @Test
    void anUnknownFlagIsRefusedNotPassedThrough() {
        assertEquals(Decision.DENIED_UNKNOWN_FLAG, pilot.decide("ping -z example.com"));
        assertEquals(Decision.DENIED_UNKNOWN_FLAG, pilot.decide("whoami /xyz"));
        assertEquals(Decision.DENIED_UNKNOWN_FLAG, pilot.decide("netstat -Z"));
        assertEquals(Decision.DENIED_UNKNOWN_FLAG, pilot.decide("tracert -j example.com")); // loose source route
    }

    @Test
    void aValueFlagWithAnOutOfRangeOrWrongTypeValueIsRefused() {
        assertEquals(Decision.DENIED_DISALLOWED_VALUE, pilot.decide("ping -n 99999 example.com")); // > max 10
        assertEquals(Decision.DENIED_DISALLOWED_VALUE, pilot.decide("ping -n 0 example.com"));      // < min 1
        assertEquals(Decision.DENIED_DISALLOWED_VALUE, pilot.decide("ping -n abc example.com"));    // not integer
        assertEquals(Decision.DENIED_DISALLOWED_VALUE, pilot.decide("ping -n -4 example.com"));     // flag-like value
        assertEquals(Decision.DENIED_DISALLOWED_VALUE, pilot.decide("netstat -p sctp"));            // not in enum
        assertEquals(Decision.DENIED_DISALLOWED_VALUE, pilot.decide("whoami /fo xml"));             // not in enum
    }

    @Test
    void operandRulesAreEnforced() {
        // no operand permitted
        assertEquals(Decision.DENIED_DISALLOWED_OPERAND, pilot.decide("hostname somehost"));
        assertEquals(Decision.DENIED_DISALLOWED_OPERAND, pilot.decide("whoami extra"));
        // required host absent
        assertEquals(Decision.DENIED_DISALLOWED_OPERAND, pilot.decide("ping -n 4"));
        assertEquals(Decision.DENIED_DISALLOWED_OPERAND, pilot.decide("tracert -d"));
        // extra operand beyond maxOperands
        assertEquals(Decision.DENIED_DISALLOWED_OPERAND, pilot.decide("ping host1 host2"));
        // an operand that is not a type-valid host (defense-in-depth beyond D-2's char class)
        assertEquals(Decision.DENIED_DISALLOWED_OPERAND, pilot.decide("ping bad=host"));
    }

    @Test
    void aCommandWithNoPolicyIsFailClosed() {
        assertEquals(Decision.DENIED_NO_POLICY, pilot.decide("ipconfig /all"));
        assertEquals(Decision.DENIED_NO_POLICY, pilot.decide("gpresult /r"));
        assertEquals(Decision.DENIED_NO_POLICY, pilot.decide("systeminfo"));
    }

    @Test
    void nullBlankOverlongAndMissingValueAreMalformed() {
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide(null));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide(""));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide("     "));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide("ping " + "1".repeat(PtyArgumentPolicy.MAX_LINE)));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide("ping -n"));        // value-flag with no value
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide("whoami /fo"));     // value-flag with no value
    }

    @Test
    void theD3PolicyCoversExactlyTheD2Allowlist() {
        // the per-argument policy and the command-name allowlist must stay in sync (no command admitted by
        // one gate but ungoverned by the other)
        assertEquals(PtyCommandGuard.PILOT_DEFAULT_ALLOWLIST, pilot.commands());
    }

    @Test
    void sensitiveValueFlagsAreExposedForTheAuditChokepoint() {
        // the pilot marks none sensitive...
        assertEquals(Set.of(), pilot.sensitiveValueFlags("ping"));
        assertEquals(Set.of(), pilot.sensitiveValueFlags("unknown-command"));
        // ...but the mechanism works (D-4 re-admits credential-bearing flags marked sensitive -> redacted)
        PtyArgumentPolicy custom = new PtyArgumentPolicy(Map.of(
                "connect", new CommandSpec(Set.of(), Map.of("/p", ValueSpec.oneOf("a", "b").asSensitive()),
                        Set.of(), OperandRule.NONE, 0)));
        assertEquals(Set.of("/p"), custom.sensitiveValueFlags("connect"));
    }

    @Test
    void onlyAllowedDecisionReportsAllowed() {
        for (Decision d : Decision.values()) {
            assertEquals(d == Decision.ALLOWED, d.allowed(), d.name());
        }
    }
}

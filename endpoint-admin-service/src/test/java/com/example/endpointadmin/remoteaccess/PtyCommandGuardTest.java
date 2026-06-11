package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.PtyCommandGuard.Decision;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Faz 22.6 D-2 — {@link PtyCommandGuard} constrained-PTY command allowlist + shell-injection rejection. */
class PtyCommandGuardTest {

    private final PtyCommandGuard pilot = new PtyCommandGuard(PtyCommandGuard.PILOT_DEFAULT_ALLOWLIST);

    @Test
    void allowlistedReadOnlyDiagnosticsAreAllowed() {
        assertEquals(Decision.ALLOWED, pilot.decide("hostname"));
        assertEquals(Decision.ALLOWED, pilot.decide("whoami"));
        assertEquals(Decision.ALLOWED, pilot.decide("whoami /groups"));     // whoami has no /S remote flag, no file-out
        assertEquals(Decision.ALLOWED, pilot.decide("ping -n 4 10.0.0.1"));
        assertEquals(Decision.ALLOWED, pilot.decide("ping 2001:db8::1"));   // IPv6 colons are safe punctuation
        assertEquals(Decision.ALLOWED, pilot.decide("tracert example.com"));
        assertEquals(Decision.ALLOWED, pilot.decide("netstat -an"));
        assertEquals(Decision.ALLOWED, pilot.decide("ver"));
    }

    @Test
    void argumentUnsafeCommandsAreExcludedFromThePilotPendingD3() {
        // Codex 019eb874 (b)/(d): these are syntactically clean (all chars in the safe class) but NOT
        // argument-safe — a flag representable in the safe class writes a file, reaches a remote host with a
        // credential on the command line (which the WORM recorder would persist), or opens an interactive
        // sub-shell. So they are refused (DENIED_NOT_ALLOWLISTED) until D-3's per-argument policy re-admits
        // them with an explicit allowed-flag set. The exploit strings themselves must be refused:
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("gpresult /X out.xml"));   // file write
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("gpresult /H out.html"));  // file write
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("systeminfo /S host /U user /P pass")); // remote+cred
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("tasklist /S host /U user /P pass"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("getmac /S host"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("driverquery /S host"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("nslookup"));              // interactive sub-shell
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("systeminfo"));
    }

    @Test
    void commandNamesAreCaseInsensitive() {
        assertEquals(Decision.ALLOWED, pilot.decide("PING 10.0.0.1"));
        assertEquals(Decision.ALLOWED, pilot.decide("HostName"));
    }

    @Test
    void leadingTrailingAndCollapsedWhitespaceIsTolerated() {
        assertEquals(Decision.ALLOWED, pilot.decide("   hostname   "));
        assertEquals(Decision.ALLOWED, pilot.decide("ping     10.0.0.1"));
    }

    @Test
    void aCommandNotOnTheAllowlistIsRefused() {
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("rm -rf /"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("calc"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("powershell"));
    }

    @Test
    void argumentMutatingCommandsAreExcludedFromThePilotAllowlist() {
        // clean SYNTAX, but each is safe only with the right argument -> needs a per-argument policy slice,
        // not a bare command-name allow; so the pilot deliberately does not allowlist them.
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("ipconfig /release"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("sc stop windefend"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("net user"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("arp -d"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, pilot.decide("route add 0.0.0.0"));
    }

    @Test
    void shellChainingPipingAndRedirectionAreUnsafe() {
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("ping 10.0.0.1; rm -rf /"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("hostname && calc"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("whoami | findstr x"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("systeminfo > dump"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("ping < input"));
    }

    @Test
    void shellExpansionAndSubstitutionAreUnsafe() {
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("ping $(whoami)"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("ping `whoami`"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("ping %USERNAME%"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("ping ${HOME}"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("tasklist *.log"));
    }

    @Test
    void multilineAndControlCharInjectionIsUnsafe() {
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("hostname\nrm -rf /"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("hostname\r\ncalc"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("host\tname"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("ping" + (char) 0 + "calc")); // NUL injection
    }

    @Test
    void pathDriveAndTraversalInTheCommandNameIsUnsafe() {
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("./sh"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("..\\..\\Windows\\System32\\cmd.exe"));
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("/bin/sh"));   // argv0 '/bin/sh' is not a bare name
        assertEquals(Decision.DENIED_UNSAFE_SYNTAX, pilot.decide("cmd.exe"));   // '.' is legal in a line but not in a name
    }

    @Test
    void nullBlankAndOverlongAreMalformed() {
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide(null));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide(""));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide("     "));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide("ping " + "1".repeat(PtyCommandGuard.MAX_LENGTH)));
    }

    @Test
    void aCustomAllowlistIsHonoured() {
        PtyCommandGuard custom = new PtyCommandGuard(Set.of("ipconfig"));
        assertEquals(Decision.ALLOWED, custom.decide("ipconfig /all"));
        assertEquals(Decision.DENIED_NOT_ALLOWLISTED, custom.decide("hostname"));
    }

    @Test
    void aMalformedAllowlistEntryFailsFastAtConstruction() {
        assertThrows(IllegalArgumentException.class, () -> new PtyCommandGuard(Set.of("rm -rf"))); // space
        assertThrows(IllegalArgumentException.class, () -> new PtyCommandGuard(Set.of("../sh")));   // path
        assertThrows(IllegalArgumentException.class, () -> new PtyCommandGuard(Set.of("a;b")));     // metacharacter
        assertThrows(IllegalArgumentException.class, () -> new PtyCommandGuard(null));
    }

    @Test
    void onlyAllowedDecisionReportsAllowed() {
        for (Decision d : Decision.values()) {
            assertEquals(d == Decision.ALLOWED, d.allowed(), d.name());
        }
    }
}

package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.RemoteOperationGuard.Decision;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.CONSTRAINED_PTY;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.FILE_TRANSFER;
import static com.example.endpointadmin.remoteaccess.RemoteSessionCapability.VIEW_ONLY;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Faz 22.6 D-1 — {@link RemoteOperationGuard} VIEW_ONLY / constrained-PTY operation gating, fail-closed. */
class RemoteOperationGuardTest {

    private final RemoteOperationGuard pilot = new RemoteOperationGuard(true);
    private final RemoteOperationGuard noPilotStrictness = new RemoteOperationGuard(false);

    @Test
    void viewOnlySessionMayOnlyViewTheScreen() {
        Set<RemoteSessionCapability> granted = Set.of(VIEW_ONLY);
        assertEquals(Decision.ALLOWED, pilot.decide(granted, RemoteOperation.SCREEN_VIEW));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.KEYBOARD_INPUT));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.PTY_COMMAND));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.CLIPBOARD_COPY));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.FILE_DOWNLOAD));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.PRIVILEGE_ELEVATE));
    }

    @Test
    void constrainedPtyMayViewTypeAndRunButNotMoreThanThat() {
        Set<RemoteSessionCapability> granted = Set.of(CONSTRAINED_PTY);
        assertEquals(Decision.ALLOWED, pilot.decide(granted, RemoteOperation.SCREEN_VIEW));
        assertEquals(Decision.ALLOWED, pilot.decide(granted, RemoteOperation.KEYBOARD_INPUT));
        assertEquals(Decision.ALLOWED, pilot.decide(granted, RemoteOperation.PTY_COMMAND));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.MOUSE_INPUT));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.CLIPBOARD_PASTE));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.FILE_UPLOAD));
        assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(granted, RemoteOperation.OPEN_PORT_FORWARD));
    }

    @Test
    void aNonPilotCapabilityIsRefusedUnderPilotStrictnessEvenIfGranted() {
        // defense-in-depth: a bug granted FILE_TRANSFER (not pilot-allowed) → the file op is still refused
        Set<RemoteSessionCapability> granted = Set.of(VIEW_ONLY, FILE_TRANSFER);
        assertEquals(Decision.DENIED_NON_PILOT, pilot.decide(granted, RemoteOperation.FILE_UPLOAD));
        // ... but the legitimate VIEW_ONLY operation still works
        assertEquals(Decision.ALLOWED, pilot.decide(granted, RemoteOperation.SCREEN_VIEW));
    }

    @Test
    void withoutPilotStrictnessAGrantedCapabilityPermitsItsOperation() {
        Set<RemoteSessionCapability> granted = Set.of(FILE_TRANSFER);
        assertEquals(Decision.ALLOWED, noPilotStrictness.decide(granted, RemoteOperation.FILE_UPLOAD));
        assertEquals(Decision.DENIED_NON_PILOT, pilot.decide(granted, RemoteOperation.FILE_UPLOAD));
    }

    @Test
    void anEmptyGrantDeniesEverything() {
        for (RemoteOperation op : RemoteOperation.values()) {
            assertEquals(Decision.DENIED_NO_CAPABILITY, pilot.decide(Set.of(), op), op.name());
        }
    }

    @Test
    void nullsAreFailClosed() {
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide(null, RemoteOperation.SCREEN_VIEW));
        assertEquals(Decision.DENIED_MALFORMED, pilot.decide(Set.of(VIEW_ONLY), null));
    }

    @Test
    void onlyAllowedDecisionIsAllowed() {
        for (Decision d : Decision.values()) {
            assertEquals(d == Decision.ALLOWED, d.allowed(), d.name());
        }
    }
}

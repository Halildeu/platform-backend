package com.example.endpointadmin.remoteaccess.bridge;

import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Event;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.State;
import com.example.endpointadmin.remoteaccess.bridge.RemoteBridgeSessionStateMachine.Transition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Faz 22.6 T-1b — {@link RemoteBridgeSessionStateMachine}: lifecycle, fail-closed illegal transitions, kill override. */
class RemoteBridgeSessionStateMachineTest {

    private final RemoteBridgeSessionStateMachine sm = new RemoteBridgeSessionStateMachine();

    private State step(State from, Event event) {
        Transition t = sm.transition(from, event);
        assertTrue(t.accepted(), from + " --" + event + "-->");
        return t.to();
    }

    @Test
    void theHappyPathReachesActiveThenCloses() {
        State s = step(State.DISABLED, Event.ENABLE);
        assertEquals(State.IDLE_CONNECTED, s);
        s = step(s, Event.REQUEST_SESSION);
        assertEquals(State.SESSION_REQUESTED, s);
        s = step(s, Event.PROMPT_CONSENT);
        assertEquals(State.CONSENT_PENDING, s);
        s = step(s, Event.CONSENT_GRANTED);
        assertEquals(State.CONSENT_GRANTED, s);
        s = step(s, Event.ACTIVATE);
        assertEquals(State.ACTIVE, s);
        assertTrue(s.isActive());
        s = step(s, Event.CLOSE);
        assertEquals(State.CLOSED, s);
        assertTrue(s.isTerminal());
    }

    @Test
    void anIllegalTransitionIsRefusedAndDoesNotAdvance() {
        Transition t = sm.transition(State.DISABLED, Event.ACTIVATE); // can't activate from disabled
        assertFalse(t.accepted());
        assertEquals(State.DISABLED, t.to()); // stays
        assertFalse(sm.transition(State.IDLE_CONNECTED, Event.CONSENT_GRANTED).accepted());
        assertFalse(sm.transition(State.ACTIVE, Event.ENABLE).accepted());
    }

    @Test
    void killOrLocalAbortAlwaysFiresFromAnyLiveState() {
        for (State from : new State[]{State.IDLE_CONNECTED, State.SESSION_REQUESTED, State.CONSENT_PENDING,
                State.CONSENT_GRANTED, State.ACTIVE, State.REVOKING}) {
            assertEquals(State.KILLED, step(from, Event.KILL), from.name());
            assertEquals(State.KILLED, step(from, Event.LOCAL_ABORT), from.name());
        }
    }

    @Test
    void consentDeniedClosesAndRevokeMovesToRevoking() {
        assertEquals(State.CLOSED, step(State.CONSENT_PENDING, Event.CONSENT_DENIED));
        assertEquals(State.REVOKING, step(State.ACTIVE, Event.REVOKE));
        assertEquals(State.CLOSED, step(State.REVOKING, Event.CLOSE));
    }

    @Test
    void terminalStatesAcceptNothing() {
        for (Event e : Event.values()) {
            assertFalse(sm.transition(State.KILLED, e).accepted(), "KILLED " + e);
            assertFalse(sm.transition(State.CLOSED, e).accepted(), "CLOSED " + e);
        }
    }

    @Test
    void nullInputsAreRefused() {
        assertFalse(sm.transition(null, Event.ENABLE).accepted());
        assertFalse(sm.transition(State.ACTIVE, null).accepted());
    }
}

package com.example.endpointadmin.remoteaccess.bridge;

/**
 * Faz 22.6 T-1b — the remote-bridge session state machine (ADR-0038 §6). Attended consent is a transport
 * PRECONDITION, modelled as the lifecycle
 * {@code DISABLED → IDLE_CONNECTED → SESSION_REQUESTED → CONSENT_PENDING → CONSENT_GRANTED → ACTIVE →
 * REVOKING/KILLED → CLOSED}. Pure, total, fail-closed: an illegal {@code (state, event)} is REFUSED (the state
 * does not advance, {@code accepted=false}) so the broker denies; a {@code KILL} or {@code LOCAL_ABORT} from
 * ANY non-terminal state always succeeds to {@link State#KILLED} (the safety override can always fire);
 * {@link State#KILLED} and {@link State#CLOSED} are terminal (irreversible).
 */
public final class RemoteBridgeSessionStateMachine {

    public enum State {
        DISABLED, IDLE_CONNECTED, SESSION_REQUESTED, CONSENT_PENDING, CONSENT_GRANTED, ACTIVE, REVOKING,
        KILLED, CLOSED;

        public boolean isTerminal() {
            return this == KILLED || this == CLOSED;
        }

        /** Only {@link #ACTIVE} admits a VIEW_ONLY frame or a PTY operation. */
        public boolean isActive() {
            return this == ACTIVE;
        }
    }

    public enum Event {
        ENABLE, REQUEST_SESSION, PROMPT_CONSENT, CONSENT_GRANTED, CONSENT_DENIED, ACTIVATE, REVOKE, KILL,
        LOCAL_ABORT, CLOSE
    }

    /** The outcome of one transition attempt. {@code accepted=false} means an illegal/refused transition. */
    public record Transition(State from, State to, boolean accepted) {
        public boolean accepted() {
            return accepted;
        }
    }

    /**
     * Total transition function. {@code null} inputs and illegal transitions are refused (no advance);
     * KILL/LOCAL_ABORT from any non-terminal always reaches {@link State#KILLED}.
     */
    public Transition transition(State from, Event event) {
        if (from == null || event == null) {
            return new Transition(from, from, false);
        }
        // the safety override: kill / local-abort can always fire from a live (non-terminal) session
        if ((event == Event.KILL || event == Event.LOCAL_ABORT) && !from.isTerminal()) {
            return new Transition(from, State.KILLED, true);
        }
        State to = switch (from) {
            case DISABLED -> event == Event.ENABLE ? State.IDLE_CONNECTED : null;
            case IDLE_CONNECTED -> event == Event.REQUEST_SESSION ? State.SESSION_REQUESTED : null;
            case SESSION_REQUESTED -> event == Event.PROMPT_CONSENT ? State.CONSENT_PENDING : null;
            case CONSENT_PENDING -> switch (event) {
                case CONSENT_GRANTED -> State.CONSENT_GRANTED;
                case CONSENT_DENIED -> State.CLOSED;
                default -> null;
            };
            case CONSENT_GRANTED -> event == Event.ACTIVATE ? State.ACTIVE : null;
            case ACTIVE -> switch (event) {
                case REVOKE -> State.REVOKING;
                case CLOSE -> State.CLOSED;
                default -> null;
            };
            case REVOKING -> event == Event.CLOSE ? State.CLOSED : null; // KILL handled by the override above
            case KILLED, CLOSED -> null; // terminal — no transition
        };
        return to == null ? new Transition(from, from, false) : new Transition(from, to, true);
    }
}

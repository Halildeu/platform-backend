package com.example.endpointadmin.remoteaccess;

/**
 * Faz 22.6 Remote Access Bridge — broker session lifecycle states (ADR-0033 §3).
 *
 * <p>This is the policy/state-machine skeleton only. It carries NO runtime: no tunnel
 * is opened, no live session is served. Runtime is gated by
 * {@link RemoteAccessProperties#enabled} (default {@code false}, ADR-0034 #1388) AND the
 * §11/D10 live-acceptance gate. See {@link RemoteSessionStateMachine} for the fail-closed
 * {@code ACTIVE} invariant.
 *
 * <p>Lifecycle (happy path):
 * <pre>
 * REQUESTED → POLICY_EVALUATING → PENDING_TARGET_CONSENT → PENDING_DUAL_APPROVAL
 *   → APPROVED → TOKEN_ISSUED → AGENT_CONNECTED → OPERATOR_CONNECTED
 *   → RECORDING_READY → ACTIVE → ENDING → ENDED
 * </pre>
 */
public enum RemoteSessionState {

    // ---- lifecycle (non-terminal) ----
    REQUESTED(false),
    POLICY_EVALUATING(false),
    PENDING_TARGET_CONSENT(false),
    PENDING_DUAL_APPROVAL(false),
    APPROVED(false),
    TOKEN_ISSUED(false),
    AGENT_CONNECTED(false),
    OPERATOR_CONNECTED(false),
    RECORDING_READY(false),
    /** The only state in which an interactive channel is open. Guarded by the fail-closed invariant. */
    ACTIVE(false),
    ENDING(false),

    // ---- terminal ----
    ENDED(true),
    DENIED(true),
    EXPIRED(true),
    REVOKED(true),
    ABORTED(true),
    FAILED_POLICY(true),
    FAILED_RECORDING(true),
    FAILED_AGENT_ATTESTATION(true);

    private final boolean terminal;

    RemoteSessionState(boolean terminal) {
        this.terminal = terminal;
    }

    /** Terminal states are irreversible — no transition may leave them. */
    public boolean isTerminal() {
        return terminal;
    }
}

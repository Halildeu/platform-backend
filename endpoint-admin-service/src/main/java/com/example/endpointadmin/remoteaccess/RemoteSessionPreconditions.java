package com.example.endpointadmin.remoteaccess;

/**
 * The six preconditions a session must satisfy to enter {@link RemoteSessionState#ACTIVE}
 * (ADR-0033 §3 fail-closed invariant). A session may go ACTIVE <b>only if all six are true</b>;
 * if any is missing it stays {@code PENDING_*} or transitions to a {@code FAILED_*} terminal.
 * "abort beats connect."
 *
 * @param policyAllow         policy evaluation = allow (not FAILED_POLICY)
 * @param targetConsent       endpoint user acknowledged the attended-session prompt (ADR-0034 D2/D6)
 * @param dualApproval        a distinct approver (approver != requester) approved (ADR-0033 §4)
 * @param tokenBound          a valid, single-use, session/device/operator-bound token is held (ADR-0033 §5)
 * @param agentAttestation    agent cert + signed-binary attestation verified
 * @param recordingWriterAck  the recorder acked READY — recording is atomic with the session (ADR-0033 §6, ADR-0034 D3)
 */
public record RemoteSessionPreconditions(
        boolean policyAllow,
        boolean targetConsent,
        boolean dualApproval,
        boolean tokenBound,
        boolean agentAttestation,
        boolean recordingWriterAck) {

    /**
     * All six preconditions satisfied → ACTIVE is permitted. Default-deny: any false blocks.
     * The hard-failure vs recoverable-pending mapping lives in
     * {@link RemoteSessionStateMachine#evaluateActivation} (single source of truth).
     */
    public boolean allSatisfied() {
        return policyAllow
                && targetConsent
                && dualApproval
                && tokenBound
                && agentAttestation
                && recordingWriterAck;
    }

    /**
     * Wither: the same preconditions with {@code tokenBound} overridden. The heartbeat samples the other
     * five from the runtime, then sets {@code tokenBound} from the authoritative {@link TokenLifecycleStore}
     * liveness check (a revoked/expired jti → {@code false} → kill).
     */
    public RemoteSessionPreconditions withTokenBound(boolean tokenBoundOverride) {
        return new RemoteSessionPreconditions(
                policyAllow, targetConsent, dualApproval, tokenBoundOverride, agentAttestation, recordingWriterAck);
    }
}

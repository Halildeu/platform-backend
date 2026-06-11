package com.example.endpointadmin.remoteaccess;

/**
 * The eight preconditions a session must satisfy to enter {@link RemoteSessionState#ACTIVE}
 * (ADR-0033 §3 fail-closed invariant). A session may go ACTIVE <b>only if all eight are true</b>;
 * if any is missing it stays {@code PENDING_*} or transitions to a {@code FAILED_*} terminal.
 * "abort beats connect."
 *
 * @param policyAllow         policy evaluation = allow (not FAILED_POLICY)
 * @param targetConsent       endpoint user acknowledged the attended-session prompt (ADR-0034 D2/D6)
 * @param dualApproval        a distinct approver (approver != requester) approved (ADR-0033 §4)
 * @param tokenBound          a valid, single-use, session/device/operator-bound token is held (ADR-0033 §5)
 * @param certValid           the presented client cert is TRUSTWORTHY (B1.2): a valid chain to a trust
 *                            anchor, not revoked (CRL/OCSP), not expired, and its revocation status freshly
 *                            known (UNKNOWN/STALE → fail-closed). Orthogonal to {@code certBound}: trust
 *                            (is this cert OK at all) vs binding (is it the RIGHT cert for this token)
 * @param certBound           the presented client-cert thumbprint satisfies the token's cert-binding
 *                            (B1.1c, RFC 8705-style): a bound token requires the exact matching presented
 *                            thumbprint; a legacy-unbound token passes only under the explicit
 *                            {@link CertBindingGuard.Policy#ALLOW_LEGACY_UNBOUND} migration flag
 * @param agentAttestation    agent cert + signed-binary attestation verified
 * @param recordingWriterAck  the recorder acked READY — recording is atomic with the session (ADR-0033 §6, ADR-0034 D3)
 */
public record RemoteSessionPreconditions(
        boolean policyAllow,
        boolean targetConsent,
        boolean dualApproval,
        boolean tokenBound,
        boolean certValid,
        boolean certBound,
        boolean agentAttestation,
        boolean recordingWriterAck) {

    /**
     * All seven preconditions satisfied → ACTIVE is permitted. Default-deny: any false blocks.
     * The hard-failure vs recoverable-pending mapping lives in
     * {@link RemoteSessionStateMachine#evaluateActivation} (single source of truth).
     */
    public boolean allSatisfied() {
        return policyAllow
                && targetConsent
                && dualApproval
                && tokenBound
                && certValid
                && certBound
                && agentAttestation
                && recordingWriterAck;
    }

    /**
     * Wither: the same preconditions with {@code tokenBound} overridden. The heartbeat samples the others
     * from the runtime, then sets {@code tokenBound} from the authoritative {@link TokenLifecycleStore}
     * liveness check (a revoked/expired jti → {@code false} → kill).
     */
    public RemoteSessionPreconditions withTokenBound(boolean tokenBoundOverride) {
        return new RemoteSessionPreconditions(
                policyAllow, targetConsent, dualApproval, tokenBoundOverride, certValid, certBound,
                agentAttestation, recordingWriterAck);
    }

    /**
     * Wither: the same preconditions with {@code certValid} overridden. The heartbeat samples the others
     * from the runtime, then sets {@code certValid} from the {@link CertTrustEvaluator} (a revoked / expired /
     * untrusted / unknown / stale cert → {@code false} → kill).
     */
    public RemoteSessionPreconditions withCertValid(boolean certValidOverride) {
        return new RemoteSessionPreconditions(
                policyAllow, targetConsent, dualApproval, tokenBound, certValidOverride, certBound,
                agentAttestation, recordingWriterAck);
    }
}

package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpAssertion;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

/**
 * Faz 22.6 D step-up handler (Codex 019ebe06) — the operator step-up challenge-response logic: issue a single
 * pending challenge for a session, then verify the operator's WebAuthn assertion against it and record the
 * result into the session. This is the agent-completable HANDLER (the same way the T-2 transport code was
 * written before a real agent connected); the operator-facing transport that AUTHENTICATES the operator and
 * carries the challenge/assertion (an operator-mTLS gRPC/REST endpoint) is the live slice-4c-transport.
 *
 * <p><b>Fail-closed, single-use, one-pending-per-session (Codex S2/S3):</b> a new {@link #issueChallenge}
 * replaces any prior challenge; {@link #verifyAndRecord} consumes it exactly once. A missing session, a
 * missing/expired/already-consumed challenge, or a non-VERIFIED verification never advances the session
 * step-up — {@code session.recordStepUp} ignores a non-VERIFIED result by its own monotonic guard.
 */
public final class OperatorStepUpHandler {

    private static final int NONCE_BYTES = 32;

    private final OperatorStepUpVerifier verifier;
    private final RemoteBridgeSessionStore store;
    private final String expectedOrigin;
    private final long challengeTtlMillis;
    private final SecureRandom random = new SecureRandom();

    public OperatorStepUpHandler(OperatorStepUpVerifier verifier, RemoteBridgeSessionStore store,
                                 String expectedOrigin, long challengeTtlMillis) {
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.store = Objects.requireNonNull(store, "store");
        if (expectedOrigin == null || expectedOrigin.isBlank()) {
            throw new IllegalArgumentException("expectedOrigin is required (origin pinning is mandatory)");
        }
        if (challengeTtlMillis <= 0) {
            throw new IllegalArgumentException("challengeTtlMillis must be positive");
        }
        this.expectedOrigin = expectedOrigin;
        this.challengeTtlMillis = challengeTtlMillis;
    }

    /**
     * Issue a fresh single-use step-up challenge for the session (a random nonce pinned to the configured
     * origin), replacing any prior pending challenge. Empty when the session is unknown.
     */
    public Optional<StepUpChallenge> issueChallenge(String sessionId, long nowEpochMillis) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        RemoteBridgeSession session = store.bySessionId(sessionId).orElse(null);
        if (session == null) {
            return Optional.empty();
        }
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        StepUpChallenge challenge = new StepUpChallenge(
                Base64.getEncoder().encodeToString(nonce), expectedOrigin, nowEpochMillis);
        session.setPendingStepUpChallenge(challenge, nowEpochMillis + challengeTtlMillis);
        return Optional.of(challenge);
    }

    /**
     * Verify the operator's assertion against the session's pending challenge and record a VERIFIED step-up.
     * Fail-closed: an unknown session or a missing/expired/already-consumed challenge yields a non-VERIFIED
     * {@code MISSING} WITHOUT calling the verifier; otherwise the verifier decides and the session records the
     * result (a non-VERIFIED verdict is a no-op on the session by its monotonic guard).
     */
    public StepUpVerification verifyAndRecord(String sessionId, StepUpAssertion assertion, long nowEpochMillis) {
        if (sessionId == null || sessionId.isBlank()) {
            return refused();
        }
        RemoteBridgeSession session = store.bySessionId(sessionId).orElse(null);
        if (session == null) {
            return refused();
        }
        StepUpChallenge challenge = session.consumePendingStepUpChallenge(nowEpochMillis).orElse(null);
        if (challenge == null) {
            return refused(); // no / expired / already-consumed challenge — never verify a replayed assertion
        }
        StepUpVerification verification = verifier.verify(challenge, assertion, nowEpochMillis);
        session.recordStepUp(verification); // non-VERIFIED → no-op (monotonic guard in the session)
        return verification;
    }

    private static StepUpVerification refused() {
        return new StepUpVerification(Verdict.MISSING, MethodStrength.NONE, 0L);
    }
}

package com.example.endpointadmin.remoteaccess.bridge.orchestrator;

import com.example.endpointadmin.remoteaccess.InMemoryOperatorStepUpVerifier;
import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpAssertion;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;
import com.example.endpointadmin.remoteaccess.RemoteSessionCapability;
import com.example.endpointadmin.remoteaccess.bridge.contract.RemoteBridgeMessages.SessionRequest;
import com.example.endpointadmin.remoteaccess.bridge.server.PeerIdentity;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D step-up handler (Codex 019ebe06) — the challenge-response is fail-closed + single-use: a verified
 * assertion against the issued challenge advances the session step-up; a missing/expired/replayed/wrong-session
 * challenge refuses without advancing.
 */
class OperatorStepUpHandlerTest {

    private static final long NOW = 100_000L;
    private static final long TTL = 60_000L;
    private static final String ORIGIN = "https://operator.acik.com";

    /** Mirror InMemoryOperatorStepUpVerifier's placeholder signature so a test can produce a "valid" assertion. */
    private static String placeholderSig(String challengeB64, String clientDataJsonB64) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((challengeB64 + "|" + clientDataJsonB64).getBytes(StandardCharsets.UTF_8));
            return java.util.Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static RemoteBridgeSessionStore storeWithSession(String sessionId) {
        RemoteBridgeSessionStore store = new RemoteBridgeSessionStore();
        PeerIdentity peer = new PeerIdentity("peer-1", Optional.of("dev-1"), List.of());
        store.open(new SessionRequest(sessionId, "dev-1", "operator@x", null,
                Set.of(RemoteSessionCapability.VIEW_ONLY)), peer, "Operator X", NOW + 60_000L, NOW);
        return store;
    }

    private static OperatorStepUpHandler handler(RemoteBridgeSessionStore store) {
        OperatorStepUpVerifier verifier =
                new InMemoryOperatorStepUpVerifier(MethodStrength.WEBAUTHN_USER_VERIFICATION, ORIGIN);
        return new OperatorStepUpHandler(verifier, store, ORIGIN, TTL);
    }

    /** Build a valid assertion for the just-issued challenge (InMemory placeholder signature). */
    private static StepUpAssertion validAssertion(StepUpChallenge challenge) {
        String clientData = "client-data-1";
        return new StepUpAssertion(clientData, "auth-data", placeholderSig(challenge.challengeB64(), clientData));
    }

    @Test
    void aVerifiedAssertionAgainstTheIssuedChallengeAdvancesTheSessionStepUp() {
        RemoteBridgeSessionStore store = storeWithSession("s1");
        OperatorStepUpHandler handler = handler(store);

        StepUpChallenge challenge = handler.issueChallenge("s1", NOW).orElseThrow();
        StepUpVerification v = handler.verifyAndRecord("s1", validAssertion(challenge), NOW);

        assertTrue(v.isVerified());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, v.achievedStrength());
        RemoteBridgeSession session = store.bySessionId("s1").orElseThrow();
        assertEquals(NOW, session.lastStepUpEpochMillis());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, session.stepUpStrength());
    }

    @Test
    void verifyWithoutAnIssuedChallengeIsRefused() {
        RemoteBridgeSessionStore store = storeWithSession("s1");
        OperatorStepUpHandler handler = handler(store);

        StepUpVerification v = handler.verifyAndRecord("s1",
                new StepUpAssertion("cd", "ad", "sig"), NOW);

        assertEquals(Verdict.MISSING, v.verdict());
        assertEquals(0L, store.bySessionId("s1").orElseThrow().lastStepUpEpochMillis());
    }

    @Test
    void anExpiredChallengeIsRefused() {
        RemoteBridgeSessionStore store = storeWithSession("s1");
        OperatorStepUpHandler handler = handler(store);

        StepUpChallenge challenge = handler.issueChallenge("s1", NOW).orElseThrow();
        // redeem after the TTL → the challenge has expired
        StepUpVerification v = handler.verifyAndRecord("s1", validAssertion(challenge), NOW + TTL + 1);

        assertEquals(Verdict.MISSING, v.verdict());
        assertEquals(0L, store.bySessionId("s1").orElseThrow().lastStepUpEpochMillis());
    }

    @Test
    void aChallengeIsSingleUse() {
        RemoteBridgeSessionStore store = storeWithSession("s1");
        OperatorStepUpHandler handler = handler(store);

        StepUpChallenge challenge = handler.issueChallenge("s1", NOW).orElseThrow();
        assertTrue(handler.verifyAndRecord("s1", validAssertion(challenge), NOW).isVerified());
        // a second redemption of the same challenge is refused (single-use)
        StepUpVerification replay = handler.verifyAndRecord("s1", validAssertion(challenge), NOW);
        assertEquals(Verdict.MISSING, replay.verdict());
    }

    @Test
    void anewIssueInvalidatesThePriorChallenge() {
        RemoteBridgeSessionStore store = storeWithSession("s1");
        OperatorStepUpHandler handler = handler(store);

        StepUpChallenge first = handler.issueChallenge("s1", NOW).orElseThrow();
        handler.issueChallenge("s1", NOW + 1); // replaces the first (one pending per session)
        // redeeming the FIRST challenge now fails — only the latest pending challenge is live
        StepUpVerification v = handler.verifyAndRecord("s1", validAssertion(first), NOW + 2);
        // the latest challenge is consumed by the assertion built for the FIRST → signature mismatch, NOT verified
        assertFalse(v.isVerified());
    }

    @Test
    void anUnknownSessionIsRefused() {
        RemoteBridgeSessionStore store = storeWithSession("s1");
        OperatorStepUpHandler handler = handler(store);

        assertTrue(handler.issueChallenge("ghost", NOW).isEmpty());
        assertEquals(Verdict.MISSING,
                handler.verifyAndRecord("ghost", new StepUpAssertion("c", "a", "s"), NOW).verdict());
    }
}

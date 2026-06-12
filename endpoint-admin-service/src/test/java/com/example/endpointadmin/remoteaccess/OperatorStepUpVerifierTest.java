package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpAssertion;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 D operator step-up verifier seam (Codex 019ebe06) — the foundation reference verifier is
 * deterministic + fail-closed: only a matching placeholder signature on the right origin VERIFIES (with the
 * configured strength); everything else refuses with NO strength.
 */
class OperatorStepUpVerifierTest {

    private static final long NOW = 1_000L;
    private static final String ORIGIN = "https://operator.acik.com";

    /** Mirror InMemoryOperatorStepUpVerifier#placeholderSignature so a test can produce a "valid" assertion. */
    private static String placeholderSig(String challengeB64, String clientDataJsonB64) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] digest = sha256.digest((challengeB64 + "|" + clientDataJsonB64).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static OperatorStepUpVerifier verifier() {
        return new InMemoryOperatorStepUpVerifier(MethodStrength.WEBAUTHN_USER_VERIFICATION, ORIGIN);
    }

    @Test
    void aMatchingAssertionVerifiesWithTheConfiguredStrength() {
        StepUpChallenge challenge = new StepUpChallenge("chal-1", ORIGIN, NOW);
        StepUpAssertion assertion = new StepUpAssertion("clientdata-1", "authdata-1",
                placeholderSig("chal-1", "clientdata-1"));

        StepUpVerification v = verifier().verify(challenge, assertion, NOW);

        assertTrue(v.isVerified());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, v.achievedStrength());
        assertEquals(NOW, v.verifiedAtEpochMillis());
    }

    @Test
    void aNullChallengeOrAssertionIsMissing() {
        StepUpAssertion assertion = new StepUpAssertion("c", "a", "s");
        assertEquals(Verdict.MISSING, verifier().verify(null, assertion, NOW).verdict());
        assertEquals(Verdict.MISSING,
                verifier().verify(new StepUpChallenge("chal", ORIGIN, NOW), null, NOW).verdict());
    }

    @Test
    void aBlankFieldIsMalformed() {
        StepUpChallenge challenge = new StepUpChallenge("chal-1", ORIGIN, NOW);
        StepUpVerification v = verifier().verify(challenge, new StepUpAssertion("clientdata", "auth", "  "), NOW);
        assertEquals(Verdict.MALFORMED, v.verdict());
    }

    @Test
    void aWrongOriginIsRefused() {
        StepUpChallenge challenge = new StepUpChallenge("chal-1", "https://evil.example", NOW);
        StepUpAssertion assertion = new StepUpAssertion("clientdata-1", "auth",
                placeholderSig("chal-1", "clientdata-1"));
        assertEquals(Verdict.ORIGIN_MISMATCH, verifier().verify(challenge, assertion, NOW).verdict());
    }

    @Test
    void aBadSignatureIsRefused() {
        StepUpChallenge challenge = new StepUpChallenge("chal-1", ORIGIN, NOW);
        StepUpAssertion assertion = new StepUpAssertion("clientdata-1", "auth", "not-the-right-signature");
        StepUpVerification v = verifier().verify(challenge, assertion, NOW);
        assertEquals(Verdict.SIGNATURE_INVALID, v.verdict());
        assertFalse(v.isVerified());
    }

    @Test
    void aNonVerifiedVerificationCarriesNoStrengthFailClosed() {
        // even if a caller tries to construct a refused verdict WITH a strength, the record nulls it to NONE
        StepUpVerification v = new StepUpVerification(Verdict.SIGNATURE_INVALID,
                MethodStrength.WEBAUTHN_USER_VERIFICATION, NOW);
        assertEquals(MethodStrength.NONE, v.achievedStrength());
        assertFalse(v.isVerified());
    }

    @Test
    void aBlankAuthenticatorDataIsMalformed() {
        // the authenticatorData carries the UP/UV flags the real verifier needs — a blank one is fail-closed
        StepUpChallenge challenge = new StepUpChallenge("chal-1", ORIGIN, NOW);
        StepUpAssertion assertion = new StepUpAssertion("clientdata-1", "  ",
                placeholderSig("chal-1", "clientdata-1"));
        assertEquals(Verdict.MALFORMED, verifier().verify(challenge, assertion, NOW).verdict());
    }

    @Test
    void anUnpinnedOriginIsRejectedAtConstruction() {
        // origin pinning is mandatory — a null/blank expected origin is fail-open and must be refused
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryOperatorStepUpVerifier(MethodStrength.WEBAUTHN_USER_VERIFICATION, null));
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryOperatorStepUpVerifier(MethodStrength.WEBAUTHN_USER_VERIFICATION, "  "));
    }

    @Test
    void aNoneGrantedStrengthIsRejectedAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new InMemoryOperatorStepUpVerifier(MethodStrength.NONE, ORIGIN));
    }

    @Test
    void aVerifiedVerificationMustCarryANonNoneStrength() {
        assertThrows(IllegalArgumentException.class,
                () -> new StepUpVerification(Verdict.VERIFIED, MethodStrength.NONE, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new StepUpVerification(Verdict.VERIFIED, null, NOW));
    }
}

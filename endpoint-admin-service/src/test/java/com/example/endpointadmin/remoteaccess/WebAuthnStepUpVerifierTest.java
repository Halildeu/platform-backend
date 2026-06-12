package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpAssertion;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpChallenge;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.StepUpVerification;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifier.Verdict;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Faz 22.6 d-stepup-2 (Codex 019ebe06 S3) — the real WebAuthn verifier is exercised against assertions that
 * are actually signed in-test (EC P-256, SHA256withECDSA): a UV assertion verifies as USER_VERIFICATION, a UP-
 * only assertion as USER_PRESENCE, and every tamper (challenge/origin/type/UP-flag/signature) fails closed.
 */
class WebAuthnStepUpVerifierTest {

    private static final long NOW = 5_000L;
    private static final String ORIGIN = "https://operator.acik.com";
    private static final String RP_ID = "operator.acik.com";
    private static final String ALG = "SHA256withECDSA";
    private static final byte[] RAW_CHALLENGE = "step-up-challenge-bytes".getBytes(StandardCharsets.UTF_8);
    private static final String CHALLENGE_B64 = Base64.getEncoder().encodeToString(RAW_CHALLENGE);
    private static final String CHALLENGE_B64URL =
            Base64.getUrlEncoder().withoutPadding().encodeToString(RAW_CHALLENGE);

    private final KeyPair keyPair = ec();

    private static KeyPair ec() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] authenticatorData(int flags) {
        return authenticatorData(flags, sha256(RP_ID));
    }

    private static byte[] authenticatorData(int flags, byte[] rpIdHash) {
        byte[] authData = new byte[37];
        System.arraycopy(rpIdHash, 0, authData, 0, 32); // rpIdHash = SHA-256(rpId)
        authData[32] = (byte) flags;
        // [33..36] signCount left zero
        return authData;
    }

    private static String clientDataJson(String type, String challengeB64Url, String origin) {
        return "{\"type\":\"" + type + "\",\"challenge\":\"" + challengeB64Url + "\",\"origin\":\"" + origin + "\"}";
    }

    /** Build a properly-signed assertion for the given clientData + flags (correct rpId). */
    private StepUpAssertion sign(String clientDataJson, int flags) {
        return signWithAuthData(clientDataJson, authenticatorData(flags));
    }

    /** Build a properly-signed assertion over an arbitrary authenticatorData (for the wrong-rpId case). */
    private StepUpAssertion signWithAuthData(String clientDataJson, byte[] authData) {
        try {
            byte[] clientDataBytes = clientDataJson.getBytes(StandardCharsets.UTF_8);
            byte[] clientDataHash = MessageDigest.getInstance("SHA-256").digest(clientDataBytes);
            byte[] signed = new byte[authData.length + clientDataHash.length];
            System.arraycopy(authData, 0, signed, 0, authData.length);
            System.arraycopy(clientDataHash, 0, signed, authData.length, clientDataHash.length);
            Signature signer = Signature.getInstance(ALG);
            signer.initSign(keyPair.getPrivate());
            signer.update(signed);
            byte[] sig = signer.sign();
            return new StepUpAssertion(
                    Base64.getEncoder().encodeToString(clientDataBytes),
                    Base64.getEncoder().encodeToString(authData),
                    Base64.getEncoder().encodeToString(sig));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WebAuthnStepUpVerifier verifier() {
        return new WebAuthnStepUpVerifier(keyPair.getPublic(), ALG, ORIGIN, RP_ID);
    }

    private static StepUpChallenge challenge() {
        return new StepUpChallenge(CHALLENGE_B64, ORIGIN, NOW);
    }

    @Test
    void aUserVerifiedAssertionVerifiesAsUserVerification() {
        StepUpAssertion assertion = sign(clientDataJson("webauthn.get", CHALLENGE_B64URL, ORIGIN),
                0x01 | 0x04); // UP + UV
        StepUpVerification v = verifier().verify(challenge(), assertion, NOW);
        assertTrue(v.isVerified());
        assertEquals(MethodStrength.WEBAUTHN_USER_VERIFICATION, v.achievedStrength());
        assertEquals(NOW, v.verifiedAtEpochMillis());
    }

    @Test
    void aUserPresentOnlyAssertionVerifiesAsUserPresence() {
        StepUpAssertion assertion = sign(clientDataJson("webauthn.get", CHALLENGE_B64URL, ORIGIN), 0x01); // UP only
        StepUpVerification v = verifier().verify(challenge(), assertion, NOW);
        assertTrue(v.isVerified());
        assertEquals(MethodStrength.WEBAUTHN_USER_PRESENCE, v.achievedStrength());
    }

    @Test
    void aMissingUserPresenceFlagIsRefused() {
        StepUpAssertion assertion = sign(clientDataJson("webauthn.get", CHALLENGE_B64URL, ORIGIN), 0x00); // no UP
        assertEquals(Verdict.USER_PRESENCE_MISSING, verifier().verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void aWrongChallengeIsRefused() {
        String otherChallenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("a-different-challenge".getBytes(StandardCharsets.UTF_8));
        StepUpAssertion assertion = sign(clientDataJson("webauthn.get", otherChallenge, ORIGIN), 0x01 | 0x04);
        assertEquals(Verdict.CHALLENGE_MISMATCH, verifier().verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void aWrongOriginIsRefused() {
        StepUpAssertion assertion = sign(clientDataJson("webauthn.get", CHALLENGE_B64URL, "https://evil.example"),
                0x01 | 0x04);
        assertEquals(Verdict.ORIGIN_MISMATCH, verifier().verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void aWrongClientDataTypeIsMalformed() {
        StepUpAssertion assertion = sign(clientDataJson("webauthn.create", CHALLENGE_B64URL, ORIGIN), 0x01 | 0x04);
        assertEquals(Verdict.MALFORMED, verifier().verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void aSignatureFromAnotherKeyIsRefused() {
        // sign with THIS keypair but verify under a DIFFERENT operator key → signature must not verify
        WebAuthnStepUpVerifier wrongKeyVerifier = new WebAuthnStepUpVerifier(ec().getPublic(), ALG, ORIGIN, RP_ID);
        StepUpAssertion assertion = sign(clientDataJson("webauthn.get", CHALLENGE_B64URL, ORIGIN), 0x01 | 0x04);
        assertEquals(Verdict.SIGNATURE_INVALID, wrongKeyVerifier.verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void anAssertionForADifferentRelyingPartyIsRefused() {
        // a signed assertion whose authenticatorData carries SHA-256(otherRpId) must be refused (Codex)
        byte[] wrongRpAuthData = authenticatorData(0x01 | 0x04, sha256("evil.example"));
        StepUpAssertion assertion = signWithAuthData(clientDataJson("webauthn.get", CHALLENGE_B64URL, ORIGIN),
                wrongRpAuthData);
        assertEquals(Verdict.RP_ID_MISMATCH, verifier().verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void anUnparseableClientDataIsMalformed() {
        StepUpAssertion assertion = new StepUpAssertion(
                Base64.getEncoder().encodeToString("not json".getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(authenticatorData(0x01 | 0x04)),
                Base64.getEncoder().encodeToString("sig".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Verdict.MALFORMED, verifier().verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void aShortAuthenticatorDataIsMalformed() {
        String clientData = clientDataJson("webauthn.get", CHALLENGE_B64URL, ORIGIN);
        StepUpAssertion assertion = new StepUpAssertion(
                Base64.getEncoder().encodeToString(clientData.getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(new byte[10]), // < 37 bytes
                Base64.getEncoder().encodeToString("sig".getBytes(StandardCharsets.UTF_8)));
        assertEquals(Verdict.MALFORMED, verifier().verify(challenge(), assertion, NOW).verdict());
    }

    @Test
    void nullAndBlankInputsFailClosed() {
        assertEquals(Verdict.MISSING, verifier().verify(null, sign(clientDataJson("webauthn.get",
                CHALLENGE_B64URL, ORIGIN), 0x01), NOW).verdict());
        assertEquals(Verdict.MISSING, verifier().verify(challenge(), null, NOW).verdict());
        assertEquals(Verdict.MALFORMED,
                verifier().verify(challenge(), new StepUpAssertion("", "", ""), NOW).verdict());
    }

    @Test
    void theCtorRejectsBadConfig() {
        assertThrows(NullPointerException.class, () -> new WebAuthnStepUpVerifier(null, ALG, ORIGIN, RP_ID));
        assertThrows(IllegalArgumentException.class,
                () -> new WebAuthnStepUpVerifier(keyPair.getPublic(), "MD5withRSA", ORIGIN, RP_ID)); // not allowlisted
        assertThrows(IllegalArgumentException.class,
                () -> new WebAuthnStepUpVerifier(keyPair.getPublic(), ALG, null, RP_ID)); // origin required
        assertThrows(IllegalArgumentException.class,
                () -> new WebAuthnStepUpVerifier(keyPair.getPublic(), ALG, ORIGIN, null)); // rpId required
    }
}

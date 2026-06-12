package com.example.endpointadmin.remoteaccess;

import com.example.endpointadmin.remoteaccess.OperatorStepUpPolicy.MethodStrength;
import com.example.endpointadmin.remoteaccess.OperatorStepUpVerifierFactory.VerifierType;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Faz 22.6 d-stepup-3 (Codex 019ebe06) — the factory's blocking matrix at construction: the placeholder
 * IN_MEMORY verifier is refused in a prod-like profile; WEBAUTHN demands a parseable operator key; everything
 * else fails fast.
 */
class OperatorStepUpVerifierFactoryTest {

    private static final String ALG = "SHA256withECDSA";
    private static final String ORIGIN = "https://operator.acik.com";
    private static final String RP_ID = "operator.acik.com";

    private static String ecPublicKeyPem() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("EC");
            g.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = g.generateKeyPair();
            String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPublic().getEncoded());
            return "-----BEGIN PUBLIC KEY-----\n" + b64 + "\n-----END PUBLIC KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void inMemoryIsTheReferenceOutsideProd() {
        OperatorStepUpVerifier v = OperatorStepUpVerifierFactory.create(VerifierType.IN_MEMORY,
                MethodStrength.WEBAUTHN_USER_VERIFICATION, null, null, ORIGIN, RP_ID, false);
        assertInstanceOf(InMemoryOperatorStepUpVerifier.class, v);
    }

    @Test
    void inMemoryIsForbiddenInAProdLikeProfile() {
        assertThrows(IllegalStateException.class, () -> OperatorStepUpVerifierFactory.create(
                VerifierType.IN_MEMORY, MethodStrength.WEBAUTHN_USER_VERIFICATION, null, null, ORIGIN, RP_ID, true));
    }

    @Test
    void webauthnWithAValidKeyIsProdLegal() {
        OperatorStepUpVerifier v = OperatorStepUpVerifierFactory.create(VerifierType.WEBAUTHN, null,
                ecPublicKeyPem(), ALG, ORIGIN, RP_ID, true);
        assertInstanceOf(WebAuthnStepUpVerifier.class, v);
    }

    @Test
    void webauthnWithoutAKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> OperatorStepUpVerifierFactory.create(
                VerifierType.WEBAUTHN, null, "  ", ALG, ORIGIN, RP_ID, false));
    }

    @Test
    void webauthnWithAnUnparseableKeyFailsFast() {
        assertThrows(IllegalStateException.class, () -> OperatorStepUpVerifierFactory.create(
                VerifierType.WEBAUTHN, null, "-----BEGIN PUBLIC KEY-----\nnot-base64\n-----END PUBLIC KEY-----\n",
                ALG, ORIGIN, RP_ID, false));
    }

    @Test
    void aNullTypeDefaultsToTheInMemoryReferenceOutsideProd() {
        OperatorStepUpVerifier v = OperatorStepUpVerifierFactory.create(null,
                MethodStrength.WEBAUTHN_USER_VERIFICATION, null, null, ORIGIN, RP_ID, false);
        assertInstanceOf(InMemoryOperatorStepUpVerifier.class, v);
    }

    @Test
    void aNullTypeIsStillForbiddenInProd() {
        assertThrows(IllegalStateException.class, () -> OperatorStepUpVerifierFactory.create(null,
                MethodStrength.WEBAUTHN_USER_VERIFICATION, null, null, ORIGIN, RP_ID, true));
    }
}

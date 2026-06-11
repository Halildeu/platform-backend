package com.example.endpointadmin.remoteaccess;

import org.junit.jupiter.api.Test;

import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Faz 22.6 B1.4c-3 — {@link PublicKeys} PEM parsing roundtrip + fail-closed. */
class PublicKeysTest {

    private static PublicKey ecPublicKey() throws GeneralSecurityException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec("secp256r1"));
        return kpg.generateKeyPair().getPublic();
    }

    private static String toPem(PublicKey key) {
        return "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(key.getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
    }

    @Test
    void parsesAnEcSubjectPublicKeyInfoPem() throws GeneralSecurityException {
        PublicKey original = ecPublicKey();
        PublicKey parsed = PublicKeys.fromPem(toPem(original));
        assertArrayEquals(original.getEncoded(), parsed.getEncoded());
    }

    @Test
    void blankPemIsFailClosed() {
        assertThrows(GeneralSecurityException.class, () -> PublicKeys.fromPem(null));
        assertThrows(GeneralSecurityException.class, () -> PublicKeys.fromPem("   "));
    }

    @Test
    void garbagePemIsFailClosed() {
        assertThrows(GeneralSecurityException.class, () ->
                PublicKeys.fromPem("-----BEGIN PUBLIC KEY-----\nnot-base64-!!!\n-----END PUBLIC KEY-----"));
        assertThrows(GeneralSecurityException.class, () ->
                PublicKeys.fromPem("-----BEGIN PUBLIC KEY-----\n" + Base64.getEncoder().encodeToString("hi".getBytes())
                        + "\n-----END PUBLIC KEY-----"));
    }
}

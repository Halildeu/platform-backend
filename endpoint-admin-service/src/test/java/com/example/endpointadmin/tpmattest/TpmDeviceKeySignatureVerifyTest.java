package com.example.endpointadmin.tpmattest;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Faz 22.6 #548 slice-1 step-5 — {@link TpmAttestationVerifier#verifyDeviceKeySignature}: an unrestricted leaf
 * device key signing an arbitrary broker context (the session binding context). Real JCA round-trip for the
 * happy path; fail-closed for a tampered signature, a non-signing key, and garbage signature bytes.
 */
class TpmDeviceKeySignatureVerifyTest {

    private static final byte[] CONTEXT = "F22.6_DEVICE_KEY_SESSION_V1 binding-bytes".getBytes();

    @Test
    void rsaDeviceKey_signsContext_verifies() throws Exception {
        KeyPair kp = rsa(3072);
        TpmPublicArea deviceKey = TpmPublicArea.parse(deviceKeyTpm2b((RSAPublicKey) kp.getPublic()), true);
        byte[] sig = rsaTpmtSignature(jcaSign(kp.getPrivate(), CONTEXT));
        assertThatCode(() -> TpmAttestationVerifier.verifyDeviceKeySignature(deviceKey, CONTEXT, sig))
                .as("a real device-key signature over the binding context verifies").doesNotThrowAnyException();
    }

    @Test
    void tamperedSignature_failsClosed() throws Exception {
        KeyPair kp = rsa(3072);
        TpmPublicArea deviceKey = TpmPublicArea.parse(deviceKeyTpm2b((RSAPublicKey) kp.getPublic()), true);
        byte[] sig = rsaTpmtSignature(jcaSign(kp.getPrivate(), CONTEXT));
        sig[sig.length - 1] ^= 0x01;
        assertDeny(deviceKey, CONTEXT, sig, TpmDenyCode.KEY_NOT_TPM_BOUND);
    }

    @Test
    void wrongContext_failsClosed() throws Exception {
        KeyPair kp = rsa(3072);
        TpmPublicArea deviceKey = TpmPublicArea.parse(deviceKeyTpm2b((RSAPublicKey) kp.getPublic()), true);
        byte[] sig = rsaTpmtSignature(jcaSign(kp.getPrivate(), CONTEXT));
        assertDeny(deviceKey, "a-different-context".getBytes(), sig, TpmDenyCode.KEY_NOT_TPM_BOUND);
    }

    @Test
    void nonSigningKey_failsClosed() throws Exception {
        KeyPair kp = rsa(3072);
        // a key WITHOUT the sign attribute (decrypt-only) must never satisfy a signature challenge
        int decryptOnly = TpmPublicArea.OBJ_FIXED_TPM | TpmPublicArea.OBJ_SENSITIVE_DATA_ORIGIN
                | TpmPublicArea.OBJ_DECRYPT;
        TpmPublicArea deviceKey = TpmPublicArea.parse(
                rsaTpm2b((RSAPublicKey) kp.getPublic(), TpmPublicArea.ALG_NULL, decryptOnly), true);
        byte[] sig = rsaTpmtSignature(jcaSign(kp.getPrivate(), CONTEXT));
        assertDeny(deviceKey, CONTEXT, sig, TpmDenyCode.KEY_NOT_TPM_BOUND);
    }

    @Test
    void garbageSignatureBytes_failsClosed() throws Exception {
        KeyPair kp = rsa(3072);
        TpmPublicArea deviceKey = TpmPublicArea.parse(deviceKeyTpm2b((RSAPublicKey) kp.getPublic()), true);
        assertDeny(deviceKey, CONTEXT, new byte[]{0x7f, 0x7f, 0x7f}, TpmDenyCode.KEY_NOT_TPM_BOUND);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static void assertDeny(TpmPublicArea key, byte[] ctx, byte[] sig, TpmDenyCode expected) {
        TpmAttestException ex = catchThrowableOfType(
                () -> TpmAttestationVerifier.verifyDeviceKeySignature(key, ctx, sig), TpmAttestException.class);
        assertThat(ex).as("expected a fail-closed TpmAttestException").isNotNull();
        assertThat(ex.denyCode()).isEqualTo(expected);
    }

    private static byte[] deviceKeyTpm2b(RSAPublicKey key) {
        int attrs = TpmPublicArea.OBJ_FIXED_TPM | TpmPublicArea.OBJ_SENSITIVE_DATA_ORIGIN | TpmPublicArea.OBJ_SIGN;
        return rsaTpm2b(key, TpmPublicArea.ALG_NULL, attrs);
    }

    private static byte[] rsaTpm2b(RSAPublicKey key, int scheme, int attrs) {
        byte[] modulus = unsignedBytes(key.getModulus());
        ByteArrayOutputStream tpmt = new ByteArrayOutputStream();
        putU16(tpmt, TpmPublicArea.ALG_RSA);
        putU16(tpmt, TpmPublicArea.ALG_SHA256);
        putU32(tpmt, attrs);
        putU16(tpmt, 0); // authPolicy
        putU16(tpmt, TpmPublicArea.ALG_NULL); // symmetric NULL
        putU16(tpmt, scheme);
        if (scheme != TpmPublicArea.ALG_NULL) {
            putU16(tpmt, TpmPublicArea.ALG_SHA256);
        }
        putU16(tpmt, key.getModulus().bitLength());
        putU32(tpmt, 0); // exponent default
        putU16(tpmt, modulus.length);
        tpmt.writeBytes(modulus);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        putU16(out, tpmt.size());
        out.writeBytes(tpmt.toByteArray());
        return out.toByteArray();
    }

    private static byte[] rsaTpmtSignature(byte[] rsaSig) {
        ByteArrayOutputStream s = new ByteArrayOutputStream();
        putU16(s, TpmPublicArea.ALG_RSASSA);
        putU16(s, TpmPublicArea.ALG_SHA256);
        putU16(s, rsaSig.length);
        s.writeBytes(rsaSig);
        return s.toByteArray();
    }

    private static byte[] jcaSign(PrivateKey key, byte[] data) throws Exception {
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(key);
        s.update(data);
        return s.sign();
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(bits);
        return kpg.generateKeyPair();
    }

    private static byte[] unsignedBytes(BigInteger v) {
        byte[] b = v.toByteArray();
        if (b.length > 1 && b[0] == 0x00) {
            byte[] t = new byte[b.length - 1];
            System.arraycopy(b, 1, t, 0, t.length);
            return t;
        }
        return b;
    }

    private static void putU16(ByteArrayOutputStream o, int v) {
        o.write((v >>> 8) & 0xFF);
        o.write(v & 0xFF);
    }

    private static void putU32(ByteArrayOutputStream o, long v) {
        o.write((int) ((v >>> 24) & 0xFF));
        o.write((int) ((v >>> 16) & 0xFF));
        o.write((int) ((v >>> 8) & 0xFF));
        o.write((int) (v & 0xFF));
    }
}

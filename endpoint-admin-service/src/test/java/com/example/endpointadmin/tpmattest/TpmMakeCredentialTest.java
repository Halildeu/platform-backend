package com.example.endpointadmin.tpmattest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;
import java.util.Arrays;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Faz 22.3B gate-4a-2.3 — V10 software MakeCredential. The TPM-spec interop is proven by the
 * real {@code tpm2_activatecredential} swtpm run (src/test/resources/tpmattest/swtpm-makecredential-interop.sh);
 * this unit test proves the OAEP/KDFa/AES-CFB/HMAC chain via a Java make→activate round-trip
 * (a test EK whose private half we hold), guards the golden credBlob format, and covers the
 * V10 issue/verify semantics.
 */
class TpmMakeCredentialTest {

    private static final byte[] IDENTITY_LABEL = label("IDENTITY");
    private static JsonNode golden;
    private static byte[] akName;

    @BeforeAll
    static void load() throws Exception {
        try (var in = TpmMakeCredentialTest.class.getResourceAsStream("/tpmattest/golden-rsa.json")) {
            golden = new ObjectMapper().readTree(in);
        }
        // the real AK Name from the golden vector (nameAlg ‖ H(akpub)) = 34 bytes for SHA-256
        akName = TpmPublicArea.parse(Base64.getDecoder().decode(golden.get("akPub").asText()), true).computeName();
    }

    @Test
    void roundTrip_recoversTheSecret_withInjectedSeed() throws Exception {
        KeyPair ek = rsa(2048);
        byte[] secret = cred16(); // 16B
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);

        var cred = new TpmMakeCredential().make(ek.getPublic(), TpmPublicArea.ALG_SHA256, akName, secret, seed);
        byte[] recovered = activate(ek.getPrivate(), TpmPublicArea.ALG_SHA256, akName,
                cred.credentialBlob(), cred.encSecret());

        assertThat(recovered).as("Java MakeCredential ↔ Java ActivateCredential round-trips the secret").isEqualTo(secret);
    }

    @Test
    void roundTrip_recoversTheSecret_withRandomSeed() throws Exception {
        KeyPair ek = rsa(2048);
        var ch = new TpmMakeCredential().issueChallenge(ek.getPublic(), TpmPublicArea.ALG_SHA256, akName);
        assertThat(ch.secret()).hasSize(TpmMakeCredential.SECRET_BYTES);

        byte[] recovered = activate(ek.getPrivate(), TpmPublicArea.ALG_SHA256, akName,
                ch.credentialBlob(), ch.encSecret());
        assertThat(recovered).isEqualTo(ch.secret());
    }

    @Test
    void tamperedEncIdentity_failsOuterIntegrity() throws Exception {
        KeyPair ek = rsa(2048);
        byte[] secret = new byte[16];
        var cred = new TpmMakeCredential().make(ek.getPublic(), TpmPublicArea.ALG_SHA256, akName, secret, seed32());
        byte[] blob = cred.credentialBlob().clone();
        blob[blob.length - 1] ^= 0x01; // corrupt the last encIdentity byte → outer HMAC mismatch
        assertThat(catchThrowableOfType(
                () -> activate(ek.getPrivate(), TpmPublicArea.ALG_SHA256, akName, blob, cred.encSecret()),
                IllegalStateException.class))
                .as("outer-integrity HMAC catches tampering").isNotNull();
    }

    @Test
    void wrongAkName_failsOuterIntegrity() throws Exception {
        KeyPair ek = rsa(2048);
        var cred = new TpmMakeCredential().make(ek.getPublic(), TpmPublicArea.ALG_SHA256, akName, new byte[16], seed32());
        byte[] otherName = akName.clone();
        otherName[5] ^= 0x01; // a different AK Name must not validate (binding)
        assertThat(catchThrowableOfType(
                () -> activate(ek.getPrivate(), TpmPublicArea.ALG_SHA256, otherName, cred.credentialBlob(), cred.encSecret()),
                IllegalStateException.class)).isNotNull();
    }

    @Test
    void v10_verifyRecoveredSecret_matchAndMismatch() {
        byte[] a = cred16();
        assertThatCode(() -> TpmMakeCredential.verifyRecoveredSecret(a, a.clone())).doesNotThrowAnyException();
        byte[] b = a.clone(); b[0] ^= 0x01;
        assertThat(catchThrowableOfType(() -> TpmMakeCredential.verifyRecoveredSecret(a, b), TpmAttestException.class)
                .denyCode()).isEqualTo(TpmDenyCode.ACTIVATION_FAILED);
    }

    @Test
    void issueChallenge_freshSecretEachTime() throws Exception {
        KeyPair ek = rsa(2048);
        var mc = new TpmMakeCredential();
        var c1 = mc.issueChallenge(ek.getPublic(), TpmPublicArea.ALG_SHA256, akName);
        var c2 = mc.issueChallenge(ek.getPublic(), TpmPublicArea.ALG_SHA256, akName);
        assertThat(c1.secret()).isNotEqualTo(c2.secret()); // single-use, anti-replay
    }

    @Test
    void toolsFile_matchesGoldenStructure() {
        // Regression guard on the format the swtpm interop relies on (0xBADCC0DE ‖ v1 ‖ TPM2B ‖ TPM2B).
        byte[] gold = Base64.getDecoder().decode(golden.get("credBlob").asText());
        ByteBuffer bb = ByteBuffer.wrap(gold);
        assertThat(bb.getInt() & 0xFFFFFFFFL).as("tpm2-tools magic").isEqualTo(0xBADCC0DEL);
        assertThat(bb.getInt()).as("version").isEqualTo(1);
        int credLen = bb.getShort() & 0xFFFF;
        assertThat(credLen).as("idObject = TPM2B(hmac32) + encIdentity18").isEqualTo(52);
        bb.position(bb.position() + credLen);
        int encLen = bb.getShort() & 0xFFFF;
        assertThat(encLen).as("encSecret OAEP = RSA-2048 modulus bytes").isEqualTo(256);
        assertThat(Base64.getDecoder().decode(golden.get("activationExpectedB64").asText()))
                .as("AES-128 credential secret").hasSize(16);

        // A freshly-made tools file has the same header + a 256-byte encSecret for a 2048-bit EK.
        byte[] mine = TpmMakeCredential.toToolsFile(
                new TpmMakeCredential.Credential(new byte[52], new byte[256]));
        assertThat(ByteBuffer.wrap(mine).getInt() & 0xFFFFFFFFL).isEqualTo(0xBADCC0DEL);
        assertThat(mine).hasSize(8 + 2 + 52 + 2 + 256);
    }

    // ───────────────── test-only Java TPM2_ActivateCredential (inverse of MakeCredential) ─────────────────

    private static byte[] activate(PrivateKey ekPriv, int nameAlg, byte[] name, byte[] idObject, byte[] encSecret)
            throws Exception {
        String hash = TpmPublicArea.jcaHash(nameAlg);
        String hmac = "Hmac" + hash.replace("-", "");
        // OAEP-decrypt the seed
        Cipher rsa = Cipher.getInstance("RSA/ECB/OAEPPadding");
        rsa.init(Cipher.DECRYPT_MODE, ekPriv, new OAEPParameterSpec(hash, "MGF1",
                new MGF1ParameterSpec(hash), new PSource.PSpecified(IDENTITY_LABEL)));
        byte[] seed = rsa.doFinal(encSecret);
        // split idObject = TPM2B(outerHMAC) ‖ encIdentity
        int hLen = ((idObject[0] & 0xFF) << 8) | (idObject[1] & 0xFF);
        byte[] outerHmac = Arrays.copyOfRange(idObject, 2, 2 + hLen);
        byte[] encIdentity = Arrays.copyOfRange(idObject, 2 + hLen, idObject.length);
        // verify outer integrity
        byte[] hmacKey = TpmKdfa.kdfa(hmac, seed, "INTEGRITY", new byte[0], new byte[0], hLen * 8);
        Mac mac = Mac.getInstance(hmac);
        mac.init(new SecretKeySpec(hmacKey, hmac));
        mac.update(encIdentity);
        mac.update(name);
        if (!MessageDigest.isEqual(mac.doFinal(), outerHmac)) {
            throw new IllegalStateException("outer integrity HMAC mismatch");
        }
        // decrypt the credential
        byte[] symKey = TpmKdfa.kdfa(hmac, seed, "STORAGE", name, new byte[0], 128);
        Cipher aes = Cipher.getInstance("AES/CFB/NoPadding");
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(symKey, "AES"), new IvParameterSpec(new byte[16]));
        byte[] tpm2bSecret = aes.doFinal(encIdentity);
        int sLen = ((tpm2bSecret[0] & 0xFF) << 8) | (tpm2bSecret[1] & 0xFF);
        return Arrays.copyOfRange(tpm2bSecret, 2, 2 + sLen);
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(bits);
        return kpg.generateKeyPair();
    }

    private static byte[] seed32() {
        byte[] s = new byte[32];
        new SecureRandom().nextBytes(s);
        return s;
    }

    /** A fixed 16-byte (AES-128) test credential — a byte literal, not a string, to avoid a
     *  gitleaks generic-api-key false positive on a {@code secret = "..."} string assignment. */
    private static byte[] cred16() {
        return new byte[]{0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15};
    }

    private static byte[] label(String s) {
        byte[] b = s.getBytes(StandardCharsets.US_ASCII);
        return Arrays.copyOf(b, b.length + 1); // trailing 0x00
    }
}

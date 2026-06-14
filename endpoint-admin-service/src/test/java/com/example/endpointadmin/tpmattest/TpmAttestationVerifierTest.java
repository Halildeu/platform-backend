package com.example.endpointadmin.tpmattest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PSSParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * Faz 22.3B gate-4a-2.2 — V4 (certify), V5 (quote), V12 (algorithm/key-bits),
 * validated against the GROUND-TRUTH swtpm golden vector (RSASSA path) plus real
 * JCA round-trips for the RSAPSS + ECDSA branches the golden does not exercise.
 */
class TpmAttestationVerifierTest {

    private static JsonNode g;
    private static byte[] akPub, certifyAttest, certifySig, devkeyPub, quoteAttest, quoteSig, nonce;
    private static TpmPublicArea ak, devkey;

    @BeforeAll
    static void load() throws Exception {
        try (var in = TpmAttestationVerifierTest.class.getResourceAsStream("/tpmattest/golden-rsa.json")) {
            g = new ObjectMapper().readTree(in);
        }
        akPub = b64("akPub");
        certifyAttest = b64("certifyAttest");
        certifySig = b64("certifySig");
        devkeyPub = b64("devkeyPub");
        quoteAttest = b64("quoteAttest");
        quoteSig = b64("quoteSig");
        nonce = HexFormat.of().parseHex(g.get("nonceHex").asText());
        ak = TpmPublicArea.parse(akPub, true);
        devkey = TpmPublicArea.parse(devkeyPub, true);
    }

    private static byte[] b64(String f) { return Base64.getDecoder().decode(g.get(f).asText()); }

    // ───────────────────────────── V4 — certify ─────────────────────────────

    @Test
    void v4_certify_happyPath() {
        assertThatCode(() -> TpmAttestationVerifier.verifyCertify(ak, certifyAttest, certifySig, devkey))
                .as("real AK certify of the real device key verifies end-to-end")
                .doesNotThrowAnyException();
    }

    @Test
    void v4_tamperedAttest_failsClosed() {
        byte[] bad = certifyAttest.clone();
        bad[20] ^= 0x01; // corrupt a byte → AK signature no longer verifies
        assertDeny(() -> TpmAttestationVerifier.verifyCertify(ak, bad, certifySig, devkey),
                TpmDenyCode.KEY_NOT_TPM_BOUND);
    }

    @Test
    void v4_wrongDeviceKey_nameMismatch_failsClosed() {
        // The AK certified the device key, NOT itself → using the AK as the "device key" mismatches the Name.
        assertDeny(() -> TpmAttestationVerifier.verifyCertify(ak, certifyAttest, certifySig, ak),
                TpmDenyCode.KEY_NOT_TPM_BOUND);
    }

    @Test
    void v4_quoteAttestRejectedByType() {
        // quoteSig is a valid AK signature over quoteAttest, so the signature step passes — but the
        // attest is a QUOTE, not a CERTIFY → V4 rejects on type.
        assertDeny(() -> TpmAttestationVerifier.verifyCertify(ak, quoteAttest, quoteSig, devkey),
                TpmDenyCode.KEY_NOT_TPM_BOUND);
    }

    // ───────────────────────────── V5 — quote ─────────────────────────────

    @Test
    void v5_quote_happyPath() {
        assertThatCode(() -> TpmAttestationVerifier.verifyQuote(ak, quoteAttest, quoteSig, nonce))
                .as("real AK quote over the issued nonce verifies end-to-end")
                .doesNotThrowAnyException();
    }

    @Test
    void v5_wrongNonce_failsClosed() {
        byte[] otherNonce = nonce.clone();
        otherNonce[0] ^= 0x01;
        assertDeny(() -> TpmAttestationVerifier.verifyQuote(ak, quoteAttest, quoteSig, otherNonce),
                TpmDenyCode.QUOTE_INVALID);
    }

    @Test
    void v5_certifyAttestRejectedByType() {
        assertDeny(() -> TpmAttestationVerifier.verifyQuote(ak, certifyAttest, certifySig, nonce),
                TpmDenyCode.QUOTE_INVALID);
    }

    @Test
    void v5_tamperedSignature_failsClosed() {
        byte[] bad = quoteSig.clone();
        bad[60] ^= 0x01; // corrupt a byte inside the RSA signature → verify fails
        assertDeny(() -> TpmAttestationVerifier.verifyQuote(ak, quoteAttest, bad, nonce),
                TpmDenyCode.QUOTE_INVALID);
    }

    // ───────────────────────────── V12 — algorithm / key-bits ─────────────────────────────

    @Test
    void v12_goldenAk2048_acceptedForAkRole() {
        assertThatCode(() -> TpmAlgorithmPolicy.requireKeyMeetsPolicy(ak.toPublicKey(), TpmAlgorithmPolicy.Role.AK))
                .as("a 2048-bit AK is acceptable (manufacturer/TPM-constrained), telemetry-warned").doesNotThrowAnyException();
    }

    @Test
    void v12_goldenEk2048_acceptedForEkRole() throws Exception {
        X509Certificate ek = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(new java.io.ByteArrayInputStream(b64("ekCertDer")));
        assertThatCode(() -> TpmAlgorithmPolicy.requireKeyMeetsPolicy(ek.getPublicKey(), TpmAlgorithmPolicy.Role.EK))
                .doesNotThrowAnyException();
    }

    @Test
    void v12_device2048Rsa_rejected() throws Exception {
        PublicKey k = rsa(2048).getPublic();
        assertDeny(() -> TpmAlgorithmPolicy.requireKeyMeetsPolicy(k, TpmAlgorithmPolicy.Role.DEVICE),
                TpmDenyCode.WEAK_ALGORITHM);
    }

    @Test
    void v12_device3072Rsa_and_ecP256_accepted() throws Exception {
        assertThatCode(() -> TpmAlgorithmPolicy.requireKeyMeetsPolicy(rsa(3072).getPublic(), TpmAlgorithmPolicy.Role.DEVICE))
                .doesNotThrowAnyException();
        assertThatCode(() -> TpmAlgorithmPolicy.requireKeyMeetsPolicy(ec("secp256r1").getPublic(), TpmAlgorithmPolicy.Role.DEVICE))
                .doesNotThrowAnyException();
    }

    @Test
    void v12_sha1Rejected() {
        assertDeny(() -> TpmAlgorithmPolicy.requireStrongHash(0x0004 /* SHA1 */), TpmDenyCode.WEAK_ALGORITHM);
        assertThatCode(() -> TpmAlgorithmPolicy.requireStrongHash(TpmPublicArea.ALG_SHA256)).doesNotThrowAnyException();
    }

    @Test
    void v12_algorithmConfusionRejected() throws Exception {
        PublicKey rsa = rsa(3072).getPublic();
        PublicKey ec = ec("secp256r1").getPublic();
        assertDeny(() -> TpmAlgorithmPolicy.requireSchemeMatchesKey(TpmPublicArea.ALG_RSASSA, ec),
                TpmDenyCode.WEAK_ALGORITHM);
        assertDeny(() -> TpmAlgorithmPolicy.requireSchemeMatchesKey(TpmPublicArea.ALG_ECDSA, rsa),
                TpmDenyCode.WEAK_ALGORITHM);
        assertThatCode(() -> TpmAlgorithmPolicy.requireSchemeMatchesKey(TpmPublicArea.ALG_RSASSA, rsa)).doesNotThrowAnyException();
        assertThatCode(() -> TpmAlgorithmPolicy.requireSchemeMatchesKey(TpmPublicArea.ALG_ECDSA, ec)).doesNotThrowAnyException();
    }

    // ───────────────────────────── parsing / T-9 ─────────────────────────────

    @Test
    void tpmtSignature_parsesGoldenAsRsassaSha256() {
        TpmtSignature s = TpmtSignature.parse(certifySig);
        assertThat(s.sigAlg()).isEqualTo(TpmPublicArea.ALG_RSASSA);
        assertThat(s.hashAlg()).isEqualTo(TpmPublicArea.ALG_SHA256);
        assertThat(s.isRsa()).isTrue();
    }

    @Test
    void tpmsAttest_trailingBytesRejected() {
        byte[] withTrailer = Arrays.copyOf(certifyAttest, certifyAttest.length + 1);
        assertThatThrownBy(() -> TpmsAttest.parse(withTrailer))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("trailing bytes");
    }

    @Test
    void tpmsAttest_badMagicRejected() {
        byte[] bad = certifyAttest.clone();
        bad[0] ^= 0x01;
        assertThatThrownBy(() -> TpmsAttest.parse(bad))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TPM_GENERATED");
    }

    // ─────────────── RSAPSS + ECDSA verify branches (real JCA, non-golden) ───────────────

    @Test
    void rawVerify_rsassa_pkcs1() throws Exception {
        KeyPair kp = rsa(3072);
        byte[] data = "faz-22.3b certify".getBytes();
        byte[] sig = jcaSign("SHA256withRSA", kp, data, null);
        assertThat(TpmAttestationVerifier.rawVerify(kp.getPublic(), TpmPublicArea.ALG_RSASSA,
                TpmPublicArea.ALG_SHA256, data, sig)).isTrue();
        sig[10] ^= 0x01;
        assertThat(TpmAttestationVerifier.rawVerify(kp.getPublic(), TpmPublicArea.ALG_RSASSA,
                TpmPublicArea.ALG_SHA256, data, sig)).isFalse();
    }

    @Test
    void rawVerify_rsapss_explicitParams() throws Exception {
        KeyPair kp = rsa(3072);
        byte[] data = "faz-22.3b quote".getBytes();
        PSSParameterSpec pss = new PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1);
        byte[] sig = jcaSign("RSASSA-PSS", kp, data, pss);
        assertThat(TpmAttestationVerifier.rawVerify(kp.getPublic(), TpmPublicArea.ALG_RSAPSS,
                TpmPublicArea.ALG_SHA256, data, sig))
                .as("RSAPSS verified with the verifier's explicit PSSParameterSpec (salt=32)").isTrue();
    }

    @Test
    void rawVerify_ecdsa_p256() throws Exception {
        KeyPair kp = ec("secp256r1");
        byte[] data = "faz-22.3b ecdsa".getBytes();
        byte[] der = jcaSign("SHA256withECDSA", kp, data, null); // JCA emits DER
        assertThat(TpmAttestationVerifier.rawVerify(kp.getPublic(), TpmPublicArea.ALG_ECDSA,
                TpmPublicArea.ALG_SHA256, data, der)).isTrue();
    }

    @Test
    void tpmtSignature_ecdsaRawRoundTripsToDer() throws Exception {
        KeyPair kp = ec("secp256r1");
        byte[] data = "faz-22.3b ecdsa-marshal".getBytes();
        byte[] der = jcaSign("SHA256withECDSA", kp, data, null);
        byte[][] rs = derToRawRs(der, 32);
        byte[] tpmtSig = marshalEcdsaTpmtSignature(rs[0], rs[1]);

        TpmtSignature parsed = TpmtSignature.parse(tpmtSig);
        assertThat(parsed.isEcdsa()).isTrue();
        // The R‖S → DER conversion must re-verify under JCA.
        assertThat(TpmAttestationVerifier.rawVerify(kp.getPublic(), TpmPublicArea.ALG_ECDSA,
                TpmPublicArea.ALG_SHA256, data, parsed.ecdsaDerSignature())).isTrue();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static void assertDeny(org.assertj.core.api.ThrowableAssert.ThrowingCallable c, TpmDenyCode expected) {
        TpmAttestException ex = catchThrowableOfType(c, TpmAttestException.class);
        assertThat(ex).as("expected a TpmAttestException").isNotNull();
        assertThat(ex.denyCode()).isEqualTo(expected);
    }

    private static KeyPair rsa(int bits) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(bits);
        return kpg.generateKeyPair();
    }

    private static KeyPair ec(String curve) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
        kpg.initialize(new ECGenParameterSpec(curve));
        return kpg.generateKeyPair();
    }

    private static byte[] jcaSign(String alg, KeyPair kp, byte[] data, PSSParameterSpec pss) throws Exception {
        Signature s = Signature.getInstance(alg);
        if (pss != null) s.setParameter(pss);
        s.initSign(kp.getPrivate());
        s.update(data);
        return s.sign();
    }

    /** Decode a DER {@code SEQUENCE{INTEGER r, INTEGER s}} into fixed-width big-endian R,S. */
    private static byte[][] derToRawRs(byte[] der, int width) {
        int i = 0;
        if (der[i++] != 0x30) throw new IllegalStateException("not a SEQUENCE");
        i++; // seq length (small)
        if (der[i++] != 0x02) throw new IllegalStateException("r not INTEGER");
        int rlen = der[i++] & 0xff;
        BigInteger r = new BigInteger(Arrays.copyOfRange(der, i, i + rlen)); i += rlen;
        if (der[i++] != 0x02) throw new IllegalStateException("s not INTEGER");
        int slen = der[i++] & 0xff;
        BigInteger s = new BigInteger(Arrays.copyOfRange(der, i, i + slen));
        return new byte[][]{ fixedWidth(r, width), fixedWidth(s, width) };
    }

    private static byte[] fixedWidth(BigInteger v, int w) {
        byte[] b = v.toByteArray();
        byte[] out = new byte[w];
        if (b.length == w) return b;
        if (b.length > w) System.arraycopy(b, b.length - w, out, 0, w); // drop sign byte
        else System.arraycopy(b, 0, out, w - b.length, b.length);
        return out;
    }

    /** TPMT_SIGNATURE for ECDSA: sigAlg(0x0018) ‖ hashAlg(SHA256) ‖ TPM2B r ‖ TPM2B s. */
    private static byte[] marshalEcdsaTpmtSignature(byte[] r, byte[] s) {
        ByteBuffer bb = ByteBuffer.allocate(2 + 2 + 2 + r.length + 2 + s.length);
        bb.putShort((short) TpmPublicArea.ALG_ECDSA);
        bb.putShort((short) TpmPublicArea.ALG_SHA256);
        bb.putShort((short) r.length); bb.put(r);
        bb.putShort((short) s.length); bb.put(s);
        return bb.array();
    }
}

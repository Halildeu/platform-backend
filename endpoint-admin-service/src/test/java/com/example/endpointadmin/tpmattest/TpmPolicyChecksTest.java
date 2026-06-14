package com.example.endpointadmin.tpmattest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.pkcs.PKCSObjectIdentifiers;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.Extensions;
import org.bouncycastle.asn1.x509.ExtensionsGenerator;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pkcs.jcajce.JcaPKCS10CertificationRequestBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/** Faz 22.3B gate-4a-2.4 — V6 (PCR policy, golden-validated) + V9 (CSR key policy, BC CSRs). */
class TpmPolicyChecksTest {

    private static TpmsAttest goldenQuote;
    private static TpmsAttest goldenCertify;
    private static final String GOLDEN_PCR_DIGEST = "f5a5fd42d16a20302798ef6ed309979b43003d2320d9f0e8ea9831a92759fb4b";
    private static final byte[] SEL_0_7 = HexFormat.of().parseHex("810000"); // PCR 0 + 7
    private static final byte[] SEL_0 = HexFormat.of().parseHex("010000");   // PCR 0 only

    @BeforeAll
    static void load() throws Exception {
        JsonNode g;
        try (var in = TpmPolicyChecksTest.class.getResourceAsStream("/tpmattest/golden-rsa.json")) {
            g = new ObjectMapper().readTree(in);
        }
        goldenQuote = TpmsAttest.parse(Base64.getDecoder().decode(g.get("quoteAttest").asText()));
        goldenCertify = TpmsAttest.parse(Base64.getDecoder().decode(g.get("certifyAttest").asText()));
    }

    private static TpmsAttest.PcrSelection sha256(byte[] bitmap) {
        return new TpmsAttest.PcrSelection(TpmPublicArea.ALG_SHA256, bitmap);
    }

    // ───────────────────────────── V6 — PCR policy ─────────────────────────────

    @Test
    void v6_golden_selectionAndDigest_pass() {
        TpmPcrPolicy policy = new TpmPcrPolicy(Set.of(sha256(SEL_0_7)), Set.of(GOLDEN_PCR_DIGEST), false);
        assertThatCode(() -> policy.verify(goldenQuote))
                .as("golden quote: exact sha256:{0,7} selection + pinned digest").doesNotThrowAnyException();
        // exposed fields sanity
        assertThat(goldenQuote.pcrSelections()).hasSize(1);
        assertThat(HexFormat.of().formatHex(goldenQuote.pcrDigest())).isEqualTo(GOLDEN_PCR_DIGEST);
    }

    @Test
    void v6_wrongSelection_failsClosed() {
        TpmPcrPolicy policy = new TpmPcrPolicy(Set.of(sha256(SEL_0)), Set.of(GOLDEN_PCR_DIGEST), false);
        assertDeny(() -> policy.verify(goldenQuote), TpmDenyCode.PCR_POLICY_FAILED);
    }

    @Test
    void v6_digestNotInAllowSet_failsClosed() {
        TpmPcrPolicy policy = new TpmPcrPolicy(Set.of(sha256(SEL_0_7)), Set.of("00".repeat(32)), false);
        assertDeny(() -> policy.verify(goldenQuote), TpmDenyCode.PCR_POLICY_FAILED);
    }

    @Test
    void v6_emptyAllowSet_failsClosed_unlessAdvisory() {
        assertDeny(() -> new TpmPcrPolicy(Set.of(sha256(SEL_0_7)), Set.of(), false).verify(goldenQuote),
                TpmDenyCode.PCR_POLICY_FAILED);
        assertThatCode(() -> new TpmPcrPolicy(Set.of(sha256(SEL_0_7)), Set.of(), true).verify(goldenQuote))
                .as("advisory operator override: selection-only").doesNotThrowAnyException();
    }

    @Test
    void v6_certifyAttestRejected() {
        TpmPcrPolicy policy = new TpmPcrPolicy(Set.of(sha256(SEL_0_7)), Set.of(GOLDEN_PCR_DIGEST), false);
        assertDeny(() -> policy.verify(goldenCertify), TpmDenyCode.PCR_POLICY_FAILED);
    }

    // ───────────────────────────── V9 — CSR key policy ─────────────────────────────

    @Test
    void v9_rsa3072_clientAuth_pass() throws Exception {
        byte[] csr = csr(rsa(3072), "SHA256withRSA", eku(KeyPurposeId.id_kp_clientAuth));
        assertThatCode(() -> TpmCsrPolicy.verify(csr)).doesNotThrowAnyException();
    }

    @Test
    void v9_rsa3072_noExtensions_pass() throws Exception {
        byte[] csr = csr(rsa(3072), "SHA256withRSA", null);
        assertThatCode(() -> TpmCsrPolicy.verify(csr)).as("EKU absent is OK (Vault sets it)").doesNotThrowAnyException();
    }

    @Test
    void v9_ecP256_pass() throws Exception {
        byte[] csr = csr(ec("secp256r1"), "SHA256withECDSA", eku(KeyPurposeId.id_kp_clientAuth));
        assertThatCode(() -> TpmCsrPolicy.verify(csr)).doesNotThrowAnyException();
    }

    @Test
    void v9_rsa2048_belowFloor_fails() throws Exception {
        byte[] csr = csr(rsa(2048), "SHA256withRSA", eku(KeyPurposeId.id_kp_clientAuth));
        assertDeny(() -> TpmCsrPolicy.verify(csr), TpmDenyCode.CSR_POLICY_VIOLATION);
    }

    @Test
    void v9_ekuServerAuth_fails() throws Exception {
        byte[] csr = csr(rsa(3072), "SHA256withRSA", eku(KeyPurposeId.id_kp_serverAuth));
        assertDeny(() -> TpmCsrPolicy.verify(csr), TpmDenyCode.CSR_POLICY_VIOLATION);
    }

    @Test
    void v9_basicConstraintsCaTrue_fails() throws Exception {
        ExtensionsGenerator eg = new ExtensionsGenerator();
        eg.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        byte[] csr = csr(rsa(3072), "SHA256withRSA", eg.generate());
        assertDeny(() -> TpmCsrPolicy.verify(csr), TpmDenyCode.CSR_POLICY_VIOLATION);
    }

    @Test
    void v9_tamperedSignature_failsClosed() throws Exception {
        byte[] csr = csr(rsa(3072), "SHA256withRSA", eku(KeyPurposeId.id_kp_clientAuth));
        csr[csr.length - 6] ^= 0x01; // corrupt the signature region → PoP invalid (or malformed)
        assertDeny(() -> TpmCsrPolicy.verify(csr), TpmDenyCode.CSR_POLICY_VIOLATION);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static Extensions eku(KeyPurposeId purpose) throws Exception {
        ExtensionsGenerator eg = new ExtensionsGenerator();
        eg.addExtension(Extension.extendedKeyUsage, false, new ExtendedKeyUsage(purpose));
        return eg.generate();
    }

    private static byte[] csr(KeyPair kp, String sigAlg, Extensions exts) throws Exception {
        var builder = new JcaPKCS10CertificationRequestBuilder(new X500Name("CN=device,O=acik"), kp.getPublic());
        if (exts != null) {
            builder.addAttribute((ASN1ObjectIdentifier) PKCSObjectIdentifiers.pkcs_9_at_extensionRequest, exts);
        }
        ContentSigner signer = new JcaContentSignerBuilder(sigAlg).setProvider(new BouncyCastleProvider()).build(kp.getPrivate());
        return builder.build(signer).getEncoded();
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

    private static void assertDeny(org.assertj.core.api.ThrowableAssert.ThrowingCallable c, TpmDenyCode expected) {
        TpmAttestException ex = catchThrowableOfType(c, TpmAttestException.class);
        assertThat(ex).as("expected TpmAttestException").isNotNull();
        assertThat(ex.denyCode()).isEqualTo(expected);
    }
}

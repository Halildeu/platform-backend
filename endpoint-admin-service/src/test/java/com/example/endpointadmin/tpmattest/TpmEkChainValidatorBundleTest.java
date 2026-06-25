package com.example.endpointadmin.tpmattest;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.6 #548 (Codex {@code 019eff49}) — backend V2 path-building CAPABILITY proof for the manufacturer
 * On-Die CA intermediate bundle. Intel fTPMs (CSME ADL PTT) submit a LEAF-ONLY EK chain (the issuing
 * intermediate is not in the leaf, has no AIA, and is unreadable from NV on many devices); the backend must
 * still build {@code leaf → intermediate(s) → pinned root} from a CONFIGURED intermediate bundle.
 *
 * <p>This proves the verifier capability with a synthetic {@code root → intermediate → EK-like leaf} chain —
 * NOT a real hardware manufacturer-root proof for any specific device. Invariants asserted (Codex matrix):
 * the trust anchor is the pinned root ONLY; configured/agent intermediates are untrusted path material that
 * can never become trust anchors; leaf-only + bundle bridges; missing intermediate fails; wrong root fails;
 * an unrelated extra cert changes nothing; a non-empty intermediate pin manifest rejects an unpinned cert.
 */
class TpmEkChainValidatorBundleTest {

    private static X509Certificate root;
    private static X509Certificate intermediate;
    private static X509Certificate leaf;
    private static X509Certificate unrelatedRoot;
    private static X509Certificate unrelatedCert; // leaf signed by an unrelated root (decoy path material)
    private static X509Certificate intermediateB; // multi-hop: root → intermediate → intermediateB → leafMultiHop
    private static X509Certificate leafMultiHop;

    @BeforeAll
    static void buildChain() throws Exception {
        java.security.Security.addProvider(new BouncyCastleProvider());
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);

        KeyPair rootKp = kpg.generateKeyPair();
        root = ca("CN=Test On-Die Root", rootKp.getPublic(), "CN=Test On-Die Root", rootKp.getPrivate());

        KeyPair intKp = kpg.generateKeyPair();
        intermediate = ca("CN=Test CSME PTT Intermediate", intKp.getPublic(), "CN=Test On-Die Root", rootKp.getPrivate());

        KeyPair leafKp = kpg.generateKeyPair();
        leaf = endEntity("CN=EK Leaf", leafKp.getPublic(), "CN=Test CSME PTT Intermediate", intKp.getPrivate());

        KeyPair otherKp = kpg.generateKeyPair();
        unrelatedRoot = ca("CN=Unrelated Root", otherKp.getPublic(), "CN=Unrelated Root", otherKp.getPrivate());
        KeyPair decoyKp = kpg.generateKeyPair();
        unrelatedCert = endEntity("CN=Decoy", decoyKp.getPublic(), "CN=Unrelated Root", otherKp.getPrivate());

        // Multi-hop chain mirroring the real Intel On-Die structure (leaf → PTT EICA → Kernel/ROM EICA → root):
        // root → intermediate → intermediateB → leafMultiHop. The configured bundle carries BOTH intermediates.
        KeyPair intBKp = kpg.generateKeyPair();
        intermediateB = ca("CN=Test CSME Kernel EICA", intBKp.getPublic(), "CN=Test CSME PTT Intermediate", intKp.getPrivate());
        KeyPair leaf2Kp = kpg.generateKeyPair();
        leafMultiHop = endEntity("CN=EK Leaf MultiHop", leaf2Kp.getPublic(), "CN=Test CSME Kernel EICA", intBKp.getPrivate());
    }

    // ───────────────────────────── the matrix ─────────────────────────────

    @Test
    void leafOnlySubmission_withConfiguredIntermediateBundle_chainsToPinnedRoot() throws Exception {
        // THE FIX: agent sends an EMPTY chain (Intel fTPM leaf-only); the configured intermediate bundle
        // bridges leaf → intermediate → pinned root.
        TpmEkChainValidator v = new TpmEkChainValidator(
                Set.of(pin(root)), List.of(root), List.of(intermediate), Set.of());
        assertThatCode(() -> v.validate(leaf, List.of()))
                .as("leaf-only + configured intermediate bundle builds to the pinned root")
                .doesNotThrowAnyException();
    }

    @Test
    void leafOnlySubmission_multiHopIntermediateBundle_chainsToPinnedRoot() throws Exception {
        // Codex 019eff49 coverage: the real Intel On-Die chain is MULTI-HOP (leaf → PTT EICA → Kernel/ROM
        // EICA → root). A leaf-only submission must build through BOTH configured intermediates to the root.
        TpmEkChainValidator v = new TpmEkChainValidator(
                Set.of(pin(root)), List.of(root), List.of(intermediate, intermediateB), Set.of());
        assertThatCode(() -> v.validate(leafMultiHop, List.of()))
                .as("leaf-only + two-hop configured intermediate bundle builds to the pinned root")
                .doesNotThrowAnyException();
        // ...and dropping the inner intermediate breaks the chain (fail-closed).
        TpmEkChainValidator missingInner = new TpmEkChainValidator(
                Set.of(pin(root)), List.of(root), List.of(intermediate), Set.of());
        assertThatThrownBy(() -> missingInner.validate(leafMultiHop, List.of()))
                .isInstanceOf(TpmEkChainValidator.EkChainException.class);
    }

    @Test
    void leafOnlySubmission_withoutAnyIntermediate_failsClosed() throws Exception {
        // Root-only trust + NO intermediate (neither configured nor agent-sent) ⇒ cannot bridge ⇒ fail-closed.
        TpmEkChainValidator v = new TpmEkChainValidator(Set.of(pin(root)), List.of(root));
        assertThatThrownBy(() -> v.validate(leaf, List.of()))
                .isInstanceOf(TpmEkChainValidator.EkChainException.class)
                .hasMessageContaining("does not chain");
    }

    @Test
    void agentSuppliedIntermediate_alsoBridges() throws Exception {
        // Same outcome when the intermediate arrives via the agent submission instead of the config bundle.
        TpmEkChainValidator v = new TpmEkChainValidator(Set.of(pin(root)), List.of(root));
        assertThatCode(() -> v.validate(leaf, List.of(intermediate)))
                .as("agent-sent intermediate bridges to the pinned root")
                .doesNotThrowAnyException();
    }

    @Test
    void configuredIntermediate_cannotBecomeTrustAnchor() throws Exception {
        // The intermediate is supplied ONLY via the intermediate bundle, with NO root in the root bundle.
        // It must NOT self-anchor — construction fail-closes (no trusted manufacturer roots).
        assertThatThrownBy(() -> new TpmEkChainValidator(
                Set.of(pin(intermediate)), List.of(), List.of(intermediate), Set.of()))
                .isInstanceOf(TpmEkChainValidator.EkChainException.class)
                .hasMessageContaining("no trusted manufacturer roots");
    }

    @Test
    void wrongRootPinned_failsClosed() throws Exception {
        // Trust anchor is an UNRELATED root; even with the correct intermediate bundle the leaf can't reach it.
        TpmEkChainValidator v = new TpmEkChainValidator(
                Set.of(pin(unrelatedRoot)), List.of(unrelatedRoot), List.of(intermediate), Set.of());
        assertThatThrownBy(() -> v.validate(leaf, List.of()))
                .isInstanceOf(TpmEkChainValidator.EkChainException.class)
                .hasMessageContaining("does not chain");
    }

    @Test
    void unrelatedExtraCert_doesNotChangeTrustOutcome() throws Exception {
        // A decoy cert (chains to an unrelated root) as extra path material must not change the outcome:
        // the real leaf still validates only via the real intermediate → pinned root.
        TpmEkChainValidator v = new TpmEkChainValidator(
                Set.of(pin(root)), List.of(root), List.of(intermediate), Set.of());
        assertThatCode(() -> v.validate(leaf, List.of(unrelatedCert)))
                .as("unrelated decoy material is ignored; real path still builds")
                .doesNotThrowAnyException();
        // ...and a decoy leaf (unrelated root) is still rejected even with everything else configured.
        assertThatThrownBy(() -> v.validate(unrelatedCert, List.of()))
                .isInstanceOf(TpmEkChainValidator.EkChainException.class);
    }

    @Test
    void intermediatePinManifest_rejectsUnpinnedConfiguredIntermediate() throws Exception {
        // Supply-chain guardrail: a NON-EMPTY intermediate pin manifest must contain every configured
        // intermediate, else construction fail-closes (no silent inclusion of an unexpected intermediate).
        assertThatThrownBy(() -> new TpmEkChainValidator(
                Set.of(pin(root)), List.of(root), List.of(intermediate), Set.of("00".repeat(32))))
                .isInstanceOf(TpmEkChainValidator.EkChainException.class)
                .hasMessageContaining("pinned intermediate manifest");
    }

    @Test
    void intermediatePinManifest_acceptsPinnedConfiguredIntermediate() throws Exception {
        // The matching manifest entry is accepted (provenance verified) and the path still builds.
        TpmEkChainValidator v = new TpmEkChainValidator(
                Set.of(pin(root)), List.of(root), List.of(intermediate), Set.of(pin(intermediate)));
        assertThatCode(() -> v.validate(leaf, List.of()))
                .as("pinned-and-matching configured intermediate is accepted")
                .doesNotThrowAnyException();
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private static String pin(X509Certificate c) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(c.getEncoded()));
    }

    private static X509Certificate ca(String subject, PublicKey subjectKey, String issuer, PrivateKey issuerKey)
            throws Exception {
        return build(subject, subjectKey, issuer, issuerKey, true);
    }

    private static X509Certificate endEntity(String subject, PublicKey subjectKey, String issuer, PrivateKey issuerKey)
            throws Exception {
        return build(subject, subjectKey, issuer, issuerKey, false);
    }

    private static X509Certificate build(String subject, PublicKey subjectKey, String issuer, PrivateKey issuerKey,
                                         boolean isCa) throws Exception {
        Instant now = Instant.now();
        var builder = new JcaX509v3CertificateBuilder(
                new X500Name(issuer),
                new BigInteger(64, new java.security.SecureRandom()).abs().add(BigInteger.ONE),
                Date.from(now.minus(1, ChronoUnit.HOURS)),
                Date.from(now.plus(3650, ChronoUnit.DAYS)),
                new X500Name(subject),
                subjectKey);
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(isCa));
        builder.addExtension(Extension.keyUsage, true,
                isCa ? new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign)
                     : new KeyUsage(KeyUsage.digitalSignature));
        var signer = new JcaContentSignerBuilder("SHA256withRSA").build(issuerKey);
        var holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }
}

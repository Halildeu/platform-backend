package com.example.endpointadmin.security;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Faz 22.3B gate-4c test CA — mints a self-signed RSA CA plus CA-signed leaf
 * certs, so {@link EnrollmentChannelResolver} tests can exercise the real
 * {@code leaf.verify(issuerKey)} channel determination and the SPKI pin. Nothing
 * here is committed key material — everything is generated per test run.
 *
 * <p>Test-scope only (BouncyCastle); production parses with the JDK X.509 parser.
 */
final class TestChannelCa {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());

    final KeyPair caKeys;
    final X509Certificate caCert;

    private TestChannelCa(KeyPair caKeys, X509Certificate caCert) {
        this.caKeys = caKeys;
        this.caCert = caCert;
    }

    static TestChannelCa create(String cn) {
        try {
            KeyPair keys = rsaKeys();
            X500Name dn = new X500Name("CN=" + cn);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    dn, BigInteger.valueOf(SERIAL.incrementAndGet()),
                    Date.from(Instant.now().minusSeconds(60)),
                    Date.from(Instant.now().plusSeconds(3600)),
                    dn, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keys.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .getCertificate(builder.build(signer));
            return new TestChannelCa(keys, cert);
        } catch (Exception e) {
            throw new IllegalStateException("test CA generation failed", e);
        }
    }

    /** Lowercase-hex SHA-256 over this CA's SubjectPublicKeyInfo (the resolver pin). */
    String spkiSha256Hex() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(caCert.getPublicKey().getEncoded()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    LeafBuilder leaf() {
        return new LeafBuilder(this);
    }

    static final class LeafBuilder {
        private final TestChannelCa ca;
        private final List<String> sanUris = new ArrayList<>();
        private boolean clientAuth = true;
        private Instant notBefore = Instant.now().minusSeconds(60);
        private Instant notAfter = Instant.now().plusSeconds(24L * 60L * 60L * 30L);
        private String subjectDn = "CN=leaf-test";

        private LeafBuilder(TestChannelCa ca) {
            this.ca = ca;
        }

        LeafBuilder sanUri(String uri) {
            this.sanUris.add(uri);
            return this;
        }

        LeafBuilder clientAuth(boolean v) {
            this.clientAuth = v;
            return this;
        }

        LeafBuilder subjectDn(String v) {
            this.subjectDn = v;
            return this;
        }

        LeafBuilder validForDays(int days) {
            Instant now = Instant.now();
            this.notBefore = now.minusSeconds(60);
            this.notAfter = now.plusSeconds((long) days * 24L * 60L * 60L);
            return this;
        }

        X509Certificate build() {
            try {
                KeyPair keys = rsaKeys();
                JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                        new X500Name(ca.caCert.getSubjectX500Principal().getName()),
                        BigInteger.valueOf(SERIAL.incrementAndGet()),
                        Date.from(notBefore),
                        Date.from(notAfter),
                        new X500Name(subjectDn),
                        keys.getPublic());
                builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
                if (clientAuth) {
                    builder.addExtension(Extension.extendedKeyUsage, false,
                            new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
                }
                if (!sanUris.isEmpty()) {
                    GeneralName[] names = sanUris.stream()
                            .map(u -> new GeneralName(GeneralName.uniformResourceIdentifier, u))
                            .toArray(GeneralName[]::new);
                    builder.addExtension(Extension.subjectAlternativeName, false,
                            new GeneralNames(names));
                }
                // Signed by the CA private key → leaf.verify(ca.caCert.getPublicKey()) holds.
                ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(ca.caKeys.getPrivate());
                return new JcaX509CertificateConverter()
                        .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                        .getCertificate(builder.build(signer));
            } catch (Exception e) {
                throw new IllegalStateException("test leaf generation failed", e);
            }
        }
    }

    private static KeyPair rsaKeys() throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        return kpg.generateKeyPair();
    }
}

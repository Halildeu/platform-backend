package com.example.endpointadmin.remoteaccess.bridge.server;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.FileWriter;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Faz 22.6 T-2c — ephemeral EC P-256 test CA for the REAL mTLS handshake tests. Generates a CA plus
 * CA-signed leaf certs (server leaf carries SAN {@code localhost}/{@code 127.0.0.1} so client-side hostname
 * verification holds) and writes PKCS#8/X.509 PEM files into a JUnit temp dir. Nothing here is committed
 * cert material — everything is generated per test run (Codex 019ebb6c boundary).
 */
final class TestMtlsCa {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private static final AtomicLong SERIAL = new AtomicLong(System.currentTimeMillis());

    final KeyPair caKeys;
    final X509Certificate caCert;

    private TestMtlsCa(KeyPair caKeys, X509Certificate caCert) {
        this.caKeys = caKeys;
        this.caCert = caCert;
    }

    record Leaf(KeyPair keys, X509Certificate cert) {
    }

    static TestMtlsCa create(String name) {
        try {
            KeyPair keys = ecKeys();
            X500Name dn = new X500Name("CN=" + name);
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    dn, BigInteger.valueOf(SERIAL.incrementAndGet()),
                    Date.from(Instant.now().minusSeconds(60)),
                    Date.from(Instant.now().plusSeconds(3600)),
                    dn, keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                    new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(keys.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(builder.build(signer));
            return new TestMtlsCa(keys, cert);
        } catch (Exception e) {
            throw new IllegalStateException("test CA generation failed", e);
        }
    }

    /** A CA-signed server leaf with SAN dNSName=localhost + iPAddress=127.0.0.1 (hostname verification). */
    Leaf serverLeaf() {
        return leaf("CN=remote-bridge-test-server", new GeneralNames(new GeneralName[] {
                new GeneralName(GeneralName.dNSName, "localhost"),
                new GeneralName(GeneralName.iPAddress, "127.0.0.1")}));
    }

    /** A CA-signed client (agent) leaf. */
    Leaf clientLeaf(String cn) {
        return leaf("CN=" + cn, null);
    }

    private Leaf leaf(String subjectDn, GeneralNames san) {
        try {
            KeyPair keys = ecKeys();
            JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                    new X500Name(caCert.getSubjectX500Principal().getName()),
                    BigInteger.valueOf(SERIAL.incrementAndGet()),
                    Date.from(Instant.now().minusSeconds(60)),
                    Date.from(Instant.now().plusSeconds(3600)),
                    new X500Name(subjectDn), keys.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            if (san != null) {
                builder.addExtension(Extension.subjectAlternativeName, false, san);
            }
            ContentSigner signer = new JcaContentSignerBuilder("SHA256withECDSA")
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).build(caKeys.getPrivate());
            X509Certificate cert = new JcaX509CertificateConverter()
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME).getCertificate(builder.build(signer));
            return new Leaf(keys, cert);
        } catch (Exception e) {
            throw new IllegalStateException("test leaf generation failed", e);
        }
    }

    private static KeyPair ecKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        return generator.generateKeyPair();
    }

    // ------------------------------------------------------------------
    // PEM files
    // ------------------------------------------------------------------

    static Path writeCertPem(Path dir, String name, X509Certificate... chain) {
        Path file = dir.resolve(name);
        try (PemWriter writer = new PemWriter(new FileWriter(file.toFile()))) {
            for (X509Certificate cert : chain) {
                writer.writeObject(new PemObject("CERTIFICATE", cert.getEncoded()));
            }
        } catch (Exception e) {
            throw new IllegalStateException("PEM write failed: " + file, e);
        }
        return file;
    }

    /** PKCS#8 ("BEGIN PRIVATE KEY") — the format grpc's TlsServerCredentials reads portably. */
    static Path writeKeyPem(Path dir, String name, KeyPair keys) {
        Path file = dir.resolve(name);
        try (PemWriter writer = new PemWriter(new FileWriter(file.toFile()))) {
            writer.writeObject(new PemObject("PRIVATE KEY", keys.getPrivate().getEncoded()));
        } catch (IOException e) {
            throw new IllegalStateException("PEM write failed: " + file, e);
        }
        return file;
    }
}

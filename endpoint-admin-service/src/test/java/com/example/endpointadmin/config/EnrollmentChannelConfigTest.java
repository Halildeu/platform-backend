package com.example.endpointadmin.config;

import com.example.endpointadmin.security.EnrollmentChannelResolver;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.StringWriter;
import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Faz 22.3B (ADR-0039) gate-4c — proves the disabled-by-default zero-effect
 * contract of {@link EnrollmentChannelConfig} (the entire safety story of this
 * additive slice) and the enabled-path PEM-parse + pin-verify wiring, using a
 * lightweight {@link ApplicationContextRunner} (no full app context).
 */
class EnrollmentChannelConfigTest {

    static {
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(EnrollmentChannelConfig.class);

    @Test
    void disabledByDefaultCreatesNoBean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(EnrollmentChannelResolver.class);
        });
    }

    @Test
    void explicitlyDisabledCreatesNoBean() {
        runner.withPropertyValues("endpoint-admin.enrollment-channel.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(EnrollmentChannelResolver.class));
    }

    @Test
    void enabledWithPinnedIssuersCreatesResolver() {
        Ca ad = Ca.mint("ad-cs-ca");
        Ca vault = Ca.mint("vault-ca");
        runner.withPropertyValues(
                        "endpoint-admin.enrollment-channel.enabled=true",
                        "endpoint-admin.enrollment-channel.ad-cs-issuer-spki-sha256=" + ad.spki,
                        "endpoint-admin.enrollment-channel.vault-issuer-spki-sha256=" + vault.spki,
                        "endpoint-admin.enrollment-channel.ad-cs-issuer-pems=" + ad.pem,
                        "endpoint-admin.enrollment-channel.vault-issuer-pems=" + vault.pem)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(EnrollmentChannelResolver.class);
                });
    }

    @Test
    void enabledButUnpinnedIssuerFailsStartup() {
        Ca ad = Ca.mint("ad-cs-ca");
        Ca vault = Ca.mint("vault-ca");
        // Supply AD CS PEM but pin the WRONG SPKI → resolver constructor fail-closes,
        // surfacing as a context startup failure (no enable-against-widened-trust).
        runner.withPropertyValues(
                        "endpoint-admin.enrollment-channel.enabled=true",
                        "endpoint-admin.enrollment-channel.ad-cs-issuer-spki-sha256=" + vault.spki,
                        "endpoint-admin.enrollment-channel.vault-issuer-spki-sha256=" + vault.spki,
                        "endpoint-admin.enrollment-channel.ad-cs-issuer-pems=" + ad.pem,
                        "endpoint-admin.enrollment-channel.vault-issuer-pems=" + vault.pem)
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .rootCause()
                            .isInstanceOf(IllegalStateException.class)
                            .hasMessageContaining("not in the pinned set");
                });
    }

    /** A throwaway self-signed CA: its PEM + the SHA-256(SPKI) pin string. */
    private record Ca(String pem, String spki) {
        static Ca mint(String cn) {
            try {
                KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
                kpg.initialize(2048);
                KeyPair keys = kpg.generateKeyPair();
                X500Name dn = new X500Name("CN=" + cn);
                JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                        dn, BigInteger.valueOf(System.nanoTime()),
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
                String spki = HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(cert.getPublicKey().getEncoded()));
                StringWriter sw = new StringWriter();
                try (PemWriter pw = new PemWriter(sw)) {
                    pw.writeObject(new PemObject("CERTIFICATE", cert.getEncoded()));
                }
                return new Ca(sw.toString(), spki);
            } catch (Exception e) {
                throw new IllegalStateException("test CA mint failed", e);
            }
        }
    }
}

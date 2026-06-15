package com.example.endpointadmin.config;

import com.example.endpointadmin.security.EnrollmentChannelResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

/**
 * Faz 22.3B (ADR-0039) gate-4c — wiring for the dual enrollment-channel resolver.
 *
 * <p>The {@link EnrollmentChannelResolver} bean is created ONLY when
 * {@code endpoint-admin.enrollment-channel.enabled=true}. Each channel's actual
 * issuer cert(s) are supplied as a PEM bundle ({@code ad-cs-issuer-pems} /
 * {@code vault-issuer-pems}) and verified at startup against the pinned SPKI
 * SHA-256 lists in {@link EnrollmentChannelProperties} — the resolver constructor
 * fail-closes (IllegalStateException) if a supplied issuer isn't pinned, if no
 * issuer is configured, or if a key is pinned under both channels, so an
 * enabled-but-misconfigured deployment fails fast at startup rather than
 * resolving against a widened or empty trust set.
 *
 * <p>When disabled (default) no bean exists; the gate-4c-2 auth wiring injects
 * this via {@code ObjectProvider}, so an absent bean leaves the existing AD CS
 * path untouched (boot-safe, zero effect).
 */
@Configuration
@EnableConfigurationProperties(EnrollmentChannelProperties.class)
public class EnrollmentChannelConfig {

    @Bean
    @ConditionalOnProperty(prefix = "endpoint-admin.enrollment-channel", name = "enabled", havingValue = "true")
    public EnrollmentChannelResolver enrollmentChannelResolver(
            EnrollmentChannelProperties properties,
            @Value("${endpoint-admin.enrollment-channel.ad-cs-issuer-pems:}") String adCsIssuerPems,
            @Value("${endpoint-admin.enrollment-channel.vault-issuer-pems:}") String vaultIssuerPems)
            throws Exception {
        return new EnrollmentChannelResolver(
                properties.adCsIssuerSpkiSha256(), parsePems(adCsIssuerPems),
                properties.vaultIssuerSpkiSha256(), parsePems(vaultIssuerPems));
    }

    private static List<X509Certificate> parsePems(String pemBundle) throws Exception {
        List<X509Certificate> certs = new ArrayList<>();
        if (pemBundle != null && !pemBundle.isBlank()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (var cert : cf.generateCertificates(
                    new ByteArrayInputStream(pemBundle.getBytes(StandardCharsets.UTF_8)))) {
                certs.add((X509Certificate) cert);
            }
        }
        return certs;
    }
}

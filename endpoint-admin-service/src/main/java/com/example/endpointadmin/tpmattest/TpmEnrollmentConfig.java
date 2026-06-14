package com.example.endpointadmin.tpmattest;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Faz 22.3B (ADR-0039) gate-4d — beans for the TPM enrollment flow.
 *
 * <p>The {@link TpmEkChainValidator} (V2) is created ONLY when the feature is enabled, from the
 * build/config-pinned manufacturer roots ({@code manufacturer-root-pems} verified against
 * {@code manufacturer-root-sha256}). Its constructor fail-closes if no trusted root is configured,
 * so an enabled-but-misconfigured deployment fails fast at startup rather than enrolling against an
 * empty trust set. When disabled (default) no validator bean exists and the controller's
 * {@code ObjectProvider} stays empty (boot-safe).
 */
@Configuration
public class TpmEnrollmentConfig {

    /** In-process software {@code TPM2_MakeCredential} (gate-4a-2.3) as an injectable bean. */
    @Bean
    public TpmMakeCredential tpmMakeCredential() {
        return new TpmMakeCredential();
    }

    @Bean
    @ConditionalOnProperty(prefix = "endpoint-admin.tpm-attest", name = "enabled", havingValue = "true")
    public TpmEkChainValidator tpmEkChainValidator(
            TpmAttestProperties properties,
            @Value("${endpoint-admin.tpm-attest.manufacturer-root-pems:}") String manufacturerRootPems)
            throws Exception {
        List<X509Certificate> roots = new ArrayList<>();
        if (manufacturerRootPems != null && !manufacturerRootPems.isBlank()) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (var cert : cf.generateCertificates(
                    new ByteArrayInputStream(manufacturerRootPems.getBytes(StandardCharsets.UTF_8)))) {
                roots.add((X509Certificate) cert);
            }
        }
        // Fail-fast (EkChainException) if roots are empty or a cert isn't pinned — no enable-without-trust.
        return new TpmEkChainValidator(Set.copyOf(properties.manufacturerRootSha256()), roots);
    }
}

package com.example.endpointadmin.tpmattest;

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
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

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
@EnableConfigurationProperties({TpmAttestProperties.class, VaultPkiProperties.class})
public class TpmEnrollmentConfig {

    /** In-process software {@code TPM2_MakeCredential} (gate-4a-2.3) as an injectable bean. */
    @Bean
    public TpmMakeCredential tpmMakeCredential() {
        return new TpmMakeCredential();
    }

    /** gate-4b Vault PKI client (L2 issuance), created only when Vault is configured+enabled. */
    @Bean
    @ConditionalOnProperty(prefix = "endpoint-admin.tpm-attest.vault", name = "enabled", havingValue = "true")
    public VaultPkiClient vaultPkiClient(VaultPkiProperties vaultProperties) {
        return new VaultPkiClient(vaultProperties);
    }

    /**
     * V6 PCR policy — created only when a required PCR selection is configured (operator opt-in).
     * The {@code pcr.allow-set} is fail-closed by default (empty → deny unless {@code pcr.advisory}).
     */
    @Bean
    @ConditionalOnProperty(prefix = "endpoint-admin.tpm-attest.pcr", name = "required-bitmap-hex")
    public TpmPcrPolicy tpmPcrPolicy(
            @Value("${endpoint-admin.tpm-attest.pcr.required-hash-alg:11}") int requiredHashAlg,
            @Value("${endpoint-admin.tpm-attest.pcr.required-bitmap-hex}") String requiredBitmapHex,
            @Value("${endpoint-admin.tpm-attest.pcr.allow-set:}") String allowSetCsv,
            @Value("${endpoint-admin.tpm-attest.pcr.advisory:false}") boolean advisory) {
        Set<TpmsAttest.PcrSelection> required = Set.of(
                new TpmsAttest.PcrSelection(requiredHashAlg, HexFormat.of().parseHex(requiredBitmapHex.trim())));
        Set<String> allow = allowSetCsv.isBlank() ? Set.of()
                : Arrays.stream(allowSetCsv.split(",")).map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        return new TpmPcrPolicy(required, allow, advisory);
    }

    @Bean
    @ConditionalOnProperty(prefix = "endpoint-admin.tpm-attest", name = "enabled", havingValue = "true")
    public TpmEkChainValidator tpmEkChainValidator(
            TpmAttestProperties properties,
            @Value("${endpoint-admin.tpm-attest.manufacturer-root-pems:}") String manufacturerRootPems,
            // Faz 22.6 #548 (Codex 019eff49): manufacturer On-Die CA intermediate bundle — untrusted
            // PKIX path-building material so a leaf-only Intel fTPM submission can still build
            // leaf → intermediate(s) → pinned root. NEVER trust anchors. Optional sha256 manifest is a
            // supply-chain guardrail (provenance), not a trust decision.
            @Value("${endpoint-admin.tpm-attest.manufacturer-intermediate-pems:}") String manufacturerIntermediatePems,
            @Value("${endpoint-admin.tpm-attest.manufacturer-intermediate-sha256:}") String manufacturerIntermediateSha256Csv)
            throws Exception {
        CertificateFactory cf = CertificateFactory.getInstance("X.509");
        List<X509Certificate> roots = parsePems(cf, manufacturerRootPems);
        List<X509Certificate> intermediates = parsePems(cf, manufacturerIntermediatePems);
        Set<String> intermediatePins = manufacturerIntermediateSha256Csv == null
                || manufacturerIntermediateSha256Csv.isBlank()
                ? Set.of()
                : Arrays.stream(manufacturerIntermediateSha256Csv.split(","))
                        .map(String::trim).filter(s -> !s.isEmpty())
                        .collect(Collectors.toUnmodifiableSet());
        // Fail-fast (EkChainException) if roots are empty / a root isn't pinned / a configured intermediate
        // is not in a non-empty pin manifest — no enable-without-trust, no silent intermediate inclusion.
        return new TpmEkChainValidator(
                Set.copyOf(properties.manufacturerRootSha256()), roots, intermediates, intermediatePins);
    }

    private static List<X509Certificate> parsePems(CertificateFactory cf, String pems) throws Exception {
        List<X509Certificate> out = new ArrayList<>();
        if (pems != null && !pems.isBlank()) {
            for (var cert : cf.generateCertificates(new ByteArrayInputStream(pems.getBytes(StandardCharsets.UTF_8)))) {
                out.add((X509Certificate) cert);
            }
        }
        return out;
    }
}

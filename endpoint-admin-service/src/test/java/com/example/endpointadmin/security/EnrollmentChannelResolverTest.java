package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.3B (ADR-0039) gate-4c — tests for {@link EnrollmentChannelResolver}:
 * cryptographic channel determination (issuer SPKI pin + {@code leaf.verify}),
 * the cross-channel SAN guard, issuer-key rotation, and the construction-time
 * fail-closed config guards.
 */
class EnrollmentChannelResolverTest {

    private static final Instant NOW = Instant.now();
    private static final UUID GUID = UUID.fromString("a1b2c3d4-1111-2222-3333-444455556666");
    private static final String ADCOMPUTER_SAN = "adcomputer:" + GUID.toString().toLowerCase();
    private static final String EK = "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00";
    private static final String TPM_SAN = "tpm:" + EK;

    private static String code(Throwable t) {
        return ((MachineCertExtractionException) t).getErrorCode();
    }

    /** Resolver pinning ONE AD CS issuer and ONE Vault issuer. */
    private static EnrollmentChannelResolver resolver(TestChannelCa ad, TestChannelCa vault) {
        return new EnrollmentChannelResolver(
                List.of(ad.spkiSha256Hex()), List.of(ad.caCert),
                List.of(vault.spkiSha256Hex()), List.of(vault.caCert));
    }

    @Test
    void adCsSignedAdcomputerLeafResolvesToAdCsChannel() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        X509Certificate leaf = ad.leaf().sanUri(ADCOMPUTER_SAN).clientAuth(true).validForDays(30).build();

        EnrollmentChannelResolver.ChannelResolution res = resolver(ad, vault).resolve(leaf, NOW);

        assertThat(res.channel()).isEqualTo(EnrollmentChannelResolver.Channel.AD_CS);
        assertThat(res).isInstanceOf(EnrollmentChannelResolver.ChannelResolution.AdCs.class);
        assertThat(((EnrollmentChannelResolver.ChannelResolution.AdCs) res).parsed().objectGuid())
                .isEqualTo(GUID);
    }

    @Test
    void vaultSignedTpmLeafResolvesToVaultChannel() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        X509Certificate leaf = vault.leaf().sanUri(TPM_SAN).clientAuth(true).validForDays(30).build();

        EnrollmentChannelResolver.ChannelResolution res = resolver(ad, vault).resolve(leaf, NOW);

        assertThat(res.channel()).isEqualTo(EnrollmentChannelResolver.Channel.VAULT_TPM);
        assertThat(((EnrollmentChannelResolver.ChannelResolution.VaultTpm) res).parsed().ekPubSha256())
                .isEqualTo(EK);
    }

    @Test
    void adCsIssuerWithTpmSanIsCrossContamination() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        // AD CS issuer signs a cert carrying the OTHER channel's SAN form.
        X509Certificate leaf = ad.leaf().sanUri(TPM_SAN).clientAuth(true).validForDays(30).build();

        assertThatThrownBy(() -> resolver(ad, vault).resolve(leaf, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHANNEL_SAN_CROSS_CONTAMINATION"));
    }

    @Test
    void vaultIssuerWithAdcomputerSanIsCrossContamination() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        X509Certificate leaf = vault.leaf().sanUri(ADCOMPUTER_SAN).clientAuth(true).validForDays(30).build();

        assertThatThrownBy(() -> resolver(ad, vault).resolve(leaf, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHANNEL_SAN_CROSS_CONTAMINATION"));
    }

    @Test
    void dualSanCertIsCrossContamination() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        // AD CS issuer signs a cert with BOTH SAN forms — the dual-SAN bypass the
        // delegate-only design would miss (MachineCertExtractor would just read the
        // adcomputer: SAN and ignore the tpm: one). The explicit guard rejects it.
        X509Certificate leaf = ad.leaf()
                .sanUri(ADCOMPUTER_SAN).sanUri(TPM_SAN)
                .clientAuth(true).validForDays(30).build();

        assertThatThrownBy(() -> resolver(ad, vault).resolve(leaf, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHANNEL_SAN_CROSS_CONTAMINATION"));
    }

    @Test
    void unknownIssuerIsUntrusted() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        TestChannelCa rogue = TestChannelCa.create("rogue-ca");
        X509Certificate leaf = rogue.leaf().sanUri(ADCOMPUTER_SAN).clientAuth(true).validForDays(30).build();

        assertThatThrownBy(() -> resolver(ad, vault).resolve(leaf, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("CHANNEL_ISSUER_UNTRUSTED"));
    }

    @Test
    void nextIssuerRotationResolves() {
        TestChannelCa adCurrent = TestChannelCa.create("ad-cs-ca-current");
        TestChannelCa adNext = TestChannelCa.create("ad-cs-ca-next");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        // Pin current+next for the AD CS channel; sign the leaf with the NEXT key.
        EnrollmentChannelResolver resolver = new EnrollmentChannelResolver(
                List.of(adCurrent.spkiSha256Hex(), adNext.spkiSha256Hex()),
                List.of(adCurrent.caCert, adNext.caCert),
                List.of(vault.spkiSha256Hex()), List.of(vault.caCert));
        X509Certificate leaf = adNext.leaf().sanUri(ADCOMPUTER_SAN).clientAuth(true).validForDays(30).build();

        EnrollmentChannelResolver.ChannelResolution res = resolver.resolve(leaf, NOW);

        assertThat(res.channel()).isEqualTo(EnrollmentChannelResolver.Channel.AD_CS);
    }

    @Test
    void delegatedExtractorErrorPropagates() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        // Correct channel (AD CS issuer + adcomputer SAN) but missing clientAuth EKU
        // → the delegated MachineCertExtractor's own CERT_* code surfaces unchanged.
        X509Certificate leaf = ad.leaf().sanUri(ADCOMPUTER_SAN).clientAuth(false).validForDays(30).build();

        assertThatThrownBy(() -> resolver(ad, vault).resolve(leaf, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("CERT_EKU_MISSING_CLIENT_AUTH"));
    }

    // ---- construction-time fail-closed config guards ----

    @Test
    void unpinnedIssuerCertFailsClosedAtConstruction() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        // Supply AD CS cert but pin the WRONG SPKI (the vault CA's) → not pinned.
        assertThatThrownBy(() -> new EnrollmentChannelResolver(
                List.of(vault.spkiSha256Hex()), List.of(ad.caCert),
                List.of(vault.spkiSha256Hex()), List.of(vault.caCert)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in the pinned set");
    }

    @Test
    void issuerCertWithoutAnyPinFailsClosed() {
        TestChannelCa ad = TestChannelCa.create("ad-cs-ca");
        TestChannelCa vault = TestChannelCa.create("vault-ca");
        assertThatThrownBy(() -> new EnrollmentChannelResolver(
                List.of(), List.of(ad.caCert),
                List.of(vault.spkiSha256Hex()), List.of(vault.caCert)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no pinned SPKI");
    }

    @Test
    void sameKeyPinnedUnderBothChannelsFailsClosed() {
        TestChannelCa shared = TestChannelCa.create("shared-ca");
        assertThatThrownBy(() -> new EnrollmentChannelResolver(
                List.of(shared.spkiSha256Hex()), List.of(shared.caCert),
                List.of(shared.spkiSha256Hex()), List.of(shared.caCert)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("both AD_CS and VAULT_TPM");
    }

    @Test
    void emptyConfigFailsClosed() {
        assertThatThrownBy(() -> new EnrollmentChannelResolver(
                List.of(), List.of(), List.of(), List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no enrollment-channel issuers configured");
    }
}

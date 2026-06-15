package com.example.endpointadmin.security;

import org.junit.jupiter.api.Test;

import java.security.cert.X509Certificate;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Faz 22.3B (ADR-0039) gate-4c — unit tests for {@link TpmVaultCertExtractor}.
 * Mirrors {@link MachineCertExtractorTest}; the SAN identity is the
 * {@code tpm:{ek_pub_sha256}} EK-public-key digest. Self-signed test certs are
 * fine: the extractor never inspects the signature (the issuer pin is
 * {@link EnrollmentChannelResolver}'s job).
 */
class TpmVaultCertExtractorTest {

    private static final Instant NOW = Instant.now();

    /** A valid lowercase 64-hex EK-public-key digest. */
    private static final String EK = "a1b2c3d4e5f60718293a4b5c6d7e8f90112233445566778899aabbccddeeff00";

    private static String code(Throwable t) {
        return ((MachineCertExtractionException) t).getErrorCode();
    }

    @Test
    void extractsEkPubSha256FromValidCert() {
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK)
                .clientAuth(true)
                .validForDays(30)
                .build();

        TpmVaultCertExtractor.ParsedVaultCert parsed = TpmVaultCertExtractor.extract(cert, NOW);

        assertThat(parsed.ekPubSha256()).isEqualTo(EK);
        assertThat(parsed.thumbprint()).matches("[0-9a-f]{64}");
        assertThat(parsed.serial()).isNotBlank();
        assertThat(parsed.notBefore()).isBefore(parsed.notAfter());
    }

    @Test
    void rejectsCertWithoutClientAuthEku() {
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK)
                .clientAuth(false)
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_EKU_MISSING_CLIENT_AUTH"));
    }

    @Test
    void rejectsCertWithoutSanUri() {
        X509Certificate cert = TestX509Certs.builder()
                .includeSanUri(false)
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_SAN_URI_MISSING"));
    }

    @Test
    void rejectsAdcomputerSanAsForeignChannel() {
        // An adcomputer: SAN is not a tpm: SAN → no matching SAN URI for this channel.
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("adcomputer:a1b2c3d4-1111-2222-3333-444455556666")
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_SAN_URI_MISSING"));
    }

    @Test
    void rejectsUppercaseHexSanUri() {
        // Pattern is lowercase-only → an uppercase digest does not match → missing.
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK.toUpperCase())
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_SAN_URI_MISSING"));
    }

    @Test
    void rejectsTooShortHexSanUri() {
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK.substring(0, 63))
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_SAN_URI_MISSING"));
    }

    @Test
    void rejectsTwoTpmSanUrisAsAmbiguous() {
        String ek2 = "00ffeeddccbbaa998877665544332211090f8e7d6c5b4a39281706f5e4d3c2b1";
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK)
                .extraSanUri("tpm:" + ek2)
                .validForDays(30)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_SAN_URI_AMBIGUOUS"));
    }

    @Test
    void rejectsExpiredCert() {
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK)
                .expiredDaysAgo(2)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_EXPIRED"));
    }

    @Test
    void rejectsNotYetValidCert() {
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK)
                .notYetValidDaysAhead(2)
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_NOT_YET_VALID"));
    }

    @Test
    void rejectsInvalidValidityWindow() {
        X509Certificate cert = TestX509Certs.builder()
                .customSanUri("tpm:" + EK)
                .validFrom(NOW.plusSeconds(3600))
                .validUntil(NOW.minusSeconds(3600))
                .build();

        assertThatThrownBy(() -> TpmVaultCertExtractor.extract(cert, NOW))
                .isInstanceOf(MachineCertExtractionException.class)
                .satisfies(t -> assertThat(code(t)).isEqualTo("VCERT_INVALID_VALIDITY"));
    }
}

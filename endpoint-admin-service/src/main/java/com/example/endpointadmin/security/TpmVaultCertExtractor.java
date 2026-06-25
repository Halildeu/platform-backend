package com.example.endpointadmin.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faz 22.3B (ADR-0039) gate-4c — parse a <b>Vault-PKI issued</b> device cert
 * (the AD-CS-less / TPM-attested enrollment channel) into the identity we
 * authenticate on. The structural deliberate mirror of {@link MachineCertExtractor}
 * (same hardening: validity window, EKU clientAuth, exactly-one SAN URI,
 * SHA-256 thumbprint) so the two channels are reviewable side-by-side, but the
 * SAN identity is the TPM Endorsement-Key public-key digest, not an AD objectGUID.
 *
 * <p>The cert MUST carry:
 * <ul>
 *   <li>EKU {@code clientAuth} (OID 1.3.6.1.5.5.7.3.2);</li>
 *   <li>EXACTLY ONE Subject Alternative Name URI of the form
 *       {@code tpm:{ek_pub_sha256}} where {@code ek_pub_sha256} is the lowercase
 *       64-hex SHA-256 of the device EK public key — this is the PRIMARY device
 *       identity, matching the {@code ek_pub_sha256} key minted by the gate-4d
 *       attestation flow;</li>
 *   <li>{@code notBefore < notAfter} and {@code now in [notBefore, notAfter]}.</li>
 * </ul>
 *
 * <p><b>Channel separation:</b> a {@code tpm:} SAN is the only form this extractor
 * accepts. A Vault-issued cert that carried an {@code adcomputer:} SAN (or no
 * {@code tpm:} SAN) fails here — the per-channel SAN pattern is half of the
 * cross-channel guard enforced by {@link EnrollmentChannelResolver} (the other
 * half is the issuer-SPKI pin). Failures raise {@link MachineCertExtractionException}
 * with a stable {@code VCERT_*} errorCode (distinct from the AD CS {@code CERT_*}
 * space) so the caller can audit the deny reason while returning a uniform status.
 *
 * <p>Production runtime uses only the standard JDK X.509 parser — no BouncyCastle.
 */
public final class TpmVaultCertExtractor {

    /** RFC 5280 GeneralName type tag for URI. */
    private static final int SAN_TYPE_URI = 6;

    /** EKU OID for TLS Web Client Authentication. */
    public static final String EKU_CLIENT_AUTH = "1.3.6.1.5.5.7.3.2";

    /**
     * SAN URI format pin: {@code tpm:} followed by the lowercase 64-hex SHA-256
     * of the device EK public key. Uppercase is deliberately rejected so the
     * parsed {@code ekPubSha256} string is exactly the canonical lookup key
     * (matching the digest the gate-4d attestation flow persists); the issuing
     * CA template / agent MUST emit lowercase.
     */
    public static final Pattern SAN_URI_PATTERN =
            Pattern.compile("^tpm:([0-9a-f]{64})$");

    private static final HexFormat HEX = HexFormat.of();

    private TpmVaultCertExtractor() {
    }

    /**
     * Extract the {@link ParsedVaultCert} or throw {@link MachineCertExtractionException}
     * with a stable {@code VCERT_*} errorCode.
     */
    public static ParsedVaultCert extract(X509Certificate cert, Instant now) {
        Objects.requireNonNull(cert, "cert");
        Objects.requireNonNull(now, "now");

        // 1. Validity window (not-before / not-after).
        Instant notBefore = cert.getNotBefore().toInstant();
        Instant notAfter = cert.getNotAfter().toInstant();
        if (!notBefore.isBefore(notAfter)) {
            throw new MachineCertExtractionException(
                    "VCERT_INVALID_VALIDITY",
                    "Vault cert validity window invalid (notBefore >= notAfter).");
        }
        if (now.isBefore(notBefore)) {
            throw new MachineCertExtractionException(
                    "VCERT_NOT_YET_VALID",
                    "Vault cert is not yet valid.");
        }
        if (!now.isBefore(notAfter)) {
            throw new MachineCertExtractionException(
                    "VCERT_EXPIRED",
                    "Vault cert is expired.");
        }

        // 2. Extended Key Usage MUST contain clientAuth.
        List<String> ekus;
        try {
            ekus = cert.getExtendedKeyUsage();
        } catch (CertificateParsingException ex) {
            throw new MachineCertExtractionException(
                    "VCERT_EKU_PARSE_FAILED",
                    "Failed to parse Vault cert EKU extension.",
                    ex);
        }
        if (ekus == null || !ekus.contains(EKU_CLIENT_AUTH)) {
            throw new MachineCertExtractionException(
                    "VCERT_EKU_MISSING_CLIENT_AUTH",
                    "Vault cert EKU does not include clientAuth (1.3.6.1.5.5.7.3.2).");
        }

        // 3. SAN URI matching tpm:{ek_pub_sha256} — exactly one.
        String ekPubSha256;
        try {
            Collection<List<?>> sans = cert.getSubjectAlternativeNames();
            Optional<String> match = findTpmSan(sans);
            if (match.isEmpty()) {
                throw new MachineCertExtractionException(
                        "VCERT_SAN_URI_MISSING",
                        "Vault cert has no SAN URI matching tpm:{ek_pub_sha256}.");
            }
            String sanUri = match.get();
            Matcher m = SAN_URI_PATTERN.matcher(sanUri);
            if (!m.matches()) {
                throw new MachineCertExtractionException(
                        "VCERT_SAN_URI_FORMAT_INVALID",
                        "SAN URI does not match tpm:{ek_pub_sha256} format.");
            }
            ekPubSha256 = m.group(1);
        } catch (CertificateParsingException ex) {
            throw new MachineCertExtractionException(
                    "VCERT_SAN_PARSE_FAILED",
                    "Failed to parse Vault cert SAN extension.",
                    ex);
        }

        // 4. Derived fields: SHA-256 thumbprint over DER-encoded cert.
        String thumbprint;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            thumbprint = HEX.formatHex(md.digest(cert.getEncoded()));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        } catch (java.security.cert.CertificateEncodingException ex) {
            throw new MachineCertExtractionException(
                    "VCERT_ENCODING_FAILED",
                    "Failed to DER-encode Vault cert for thumbprint.",
                    ex);
        }

        String serial = cert.getSerialNumber().toString(16);
        String issuer = cert.getIssuerX500Principal().getName();
        String subject = cert.getSubjectX500Principal().getName();

        return new ParsedVaultCert(
                ekPubSha256,
                serial,
                thumbprint,
                issuer,
                subject,
                notBefore,
                notAfter
        );
    }

    /**
     * Find the single {@code tpm:{ek_pub_sha256}} SAN URI in the cert.
     *
     * <p>Mirrors {@link MachineCertExtractor}'s exactly-one rule: a misissued
     * cert with two {@code tpm:} SANs would otherwise allow first-match-wins
     * ambiguity — one cert authenticating as two distinct EK identities
     * depending on iteration order. The strict count check raises
     * {@code VCERT_SAN_URI_AMBIGUOUS}.
     */
    private static Optional<String> findTpmSan(Collection<List<?>> sans) {
        if (sans == null) {
            return Optional.empty();
        }
        String firstMatch = null;
        int matchCount = 0;
        int foreignUriCount = 0;
        for (List<?> entry : sans) {
            if (entry.size() < 2) {
                continue;
            }
            Object typeTag = entry.get(0);
            Object value = entry.get(1);
            if (!(typeTag instanceof Integer type) || type != SAN_TYPE_URI) {
                continue;
            }
            if (!(value instanceof String uri)) {
                continue;
            }
            if (SAN_URI_PATTERN.matcher(uri).matches()) {
                if (firstMatch == null) {
                    firstMatch = uri;
                }
                matchCount++;
            } else {
                foreignUriCount++;
            }
        }
        if (matchCount > 1) {
            throw new MachineCertExtractionException(
                    "VCERT_SAN_URI_AMBIGUOUS",
                    "Vault cert has " + matchCount + " tpm:{ek_pub_sha256} SAN URIs; "
                            + "exactly one is required.");
        }
        // Faz 22.6 #548 Phase 1.5 (G1, Codex 019eff93): a VALID TPM device cert carries EXACTLY one tpm:{ek}
        // URI SAN and NO other URI SAN. An extra (foreign) URI SAN ALONGSIDE the tpm SAN is an unexpected
        // identity surface → reject fail-closed. (When there is NO valid tpm SAN, a foreign/malformed URI is
        // simply "no tpm SAN" → fall through to VCERT_SAN_URI_MISSING, preserving the per-channel contract.)
        if (matchCount == 1 && foreignUriCount > 0) {
            throw new MachineCertExtractionException(
                    "VCERT_SAN_UNEXPECTED_URI",
                    "Vault cert carries an additional URI SAN beyond tpm:{ek_pub_sha256}.");
        }
        return Optional.ofNullable(firstMatch);
    }

    /**
     * Parsed identity of a Vault-PKI issued device cert.
     *
     * @param ekPubSha256 lowercase 64-hex SHA-256 of the device EK public key
     *                    (the PRIMARY device identity for the TPM channel)
     */
    public record ParsedVaultCert(
            String ekPubSha256,
            String serial,
            String thumbprint,
            String issuer,
            String subject,
            Instant notBefore,
            Instant notAfter
    ) {
    }
}

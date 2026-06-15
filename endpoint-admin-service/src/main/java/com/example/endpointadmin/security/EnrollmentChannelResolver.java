package com.example.endpointadmin.security;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.cert.CertificateParsingException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Faz 22.3B (ADR-0039) gate-4c — decide which enrollment channel a presented
 * mTLS client cert belongs to, then delegate to that channel's extractor.
 *
 * <p>Two channels coexist on the same endpoint:
 * <ul>
 *   <li><b>AD CS</b> (Codex's path) — Active Directory machine certs, SAN URI
 *       {@code adcomputer:{objectGUID}}, parsed by {@link MachineCertExtractor}
 *       (which this resolver delegates to UNCHANGED);</li>
 *   <li><b>Vault-TPM</b> (the AD-CS-less path) — Vault-PKI issued device certs,
 *       SAN URI {@code tpm:{ek_pub_sha256}}, parsed by
 *       {@link TpmVaultCertExtractor}.</li>
 * </ul>
 *
 * <p><b>Channel determination is cryptographic, not heuristic.</b> The channel
 * is decided by verifying the leaf's signature against the pinned issuer public
 * keys — never by inspecting the SAN string. Each channel pins its issuer key(s)
 * by {@code SHA-256(SubjectPublicKeyInfo)} (a current+next list for rotation,
 * mirroring {@link TpmEkChainValidator}'s build-time root pinning). A leaf that
 * verifies under no pinned issuer → {@code CHANNEL_ISSUER_UNTRUSTED}; under
 * issuers from two different channels → {@code CHANNEL_ISSUER_AMBIGUOUS}.
 *
 * <p><b>Cross-channel guard (defense-in-depth).</b> After the issuer fixes the
 * channel, the cert MUST NOT carry a SAN URI belonging to the OTHER channel —
 * an AD-CS-signed cert with a {@code tpm:} SAN, or a Vault-signed cert with an
 * {@code adcomputer:} SAN, is rejected with {@code CHANNEL_SAN_CROSS_CONTAMINATION}
 * BEFORE delegation. This closes the dual-SAN misissuance hole that pure
 * delegation would miss (the channel extractor only inspects its own SAN form).
 * The issuer pin alone already makes cross-issuance impossible; this is the
 * second, independent layer.
 *
 * <p>All deny reasons surface as {@link MachineCertExtractionException} with a
 * stable {@code CHANNEL_*} errorCode (the channel extractors keep their own
 * {@code CERT_*} / {@code VCERT_*} codes), so the eventual auth wiring maps every
 * failure to one uniform status while preserving an auditable reason.
 *
 * <p>Production runtime uses only the standard JDK X.509 verify — no BouncyCastle.
 */
public final class EnrollmentChannelResolver {

    private static final HexFormat HEX = HexFormat.of();

    /** Enrollment channel a cert was issued through. */
    public enum Channel {
        AD_CS,
        VAULT_TPM
    }

    /** Resolution outcome: the channel plus the parsed channel-specific identity. */
    public sealed interface ChannelResolution permits ChannelResolution.AdCs, ChannelResolution.VaultTpm {
        Channel channel();

        record AdCs(MachineCertExtractor.ParsedCert parsed) implements ChannelResolution {
            @Override
            public Channel channel() {
                return Channel.AD_CS;
            }
        }

        record VaultTpm(TpmVaultCertExtractor.ParsedVaultCert parsed) implements ChannelResolution {
            @Override
            public Channel channel() {
                return Channel.VAULT_TPM;
            }
        }
    }

    private record PinnedIssuer(Channel channel, PublicKey publicKey) {
    }

    private static final int SAN_TYPE_URI = 6;

    private final List<PinnedIssuer> pinnedIssuers;

    /**
     * @param adCsPinnedSpkiSha256 lowercase-hex SHA-256(SPKI) of the allowed AD CS issuer key(s)
     * @param adCsIssuers          the actual AD CS issuer certs (current+next)
     * @param vaultPinnedSpkiSha256 lowercase-hex SHA-256(SPKI) of the allowed Vault issuer key(s)
     * @param vaultIssuers          the actual Vault issuer certs (current+next)
     * @throws IllegalStateException if a supplied issuer's SPKI is not in its channel's pinned set
     *                               (config fail-closed: the trust set can never silently widen), if
     *                               no issuer is configured at all, or if the same key is pinned under
     *                               both channels (which would make every cert it signs ambiguous)
     */
    public EnrollmentChannelResolver(Collection<String> adCsPinnedSpkiSha256,
                                     List<X509Certificate> adCsIssuers,
                                     Collection<String> vaultPinnedSpkiSha256,
                                     List<X509Certificate> vaultIssuers) {
        List<PinnedIssuer> issuers = new ArrayList<>();
        issuers.addAll(pinChannel(Channel.AD_CS, adCsPinnedSpkiSha256, adCsIssuers));
        issuers.addAll(pinChannel(Channel.VAULT_TPM, vaultPinnedSpkiSha256, vaultIssuers));
        if (issuers.isEmpty()) {
            throw new IllegalStateException(
                    "no enrollment-channel issuers configured (resolver would deny every cert)");
        }
        // Same key pinned under both channels => every cert it signs is ambiguous. Fail fast.
        for (int i = 0; i < issuers.size(); i++) {
            for (int j = i + 1; j < issuers.size(); j++) {
                if (issuers.get(i).channel() != issuers.get(j).channel()
                        && spkiEquals(issuers.get(i).publicKey(), issuers.get(j).publicKey())) {
                    throw new IllegalStateException(
                            "the same issuer public key is pinned under both AD_CS and VAULT_TPM");
                }
            }
        }
        this.pinnedIssuers = List.copyOf(issuers);
    }

    private static List<PinnedIssuer> pinChannel(Channel channel,
                                                 Collection<String> pinnedSpkiSha256,
                                                 List<X509Certificate> channelIssuers) {
        if (channelIssuers == null || channelIssuers.isEmpty()) {
            return List.of();
        }
        List<byte[]> pins = new ArrayList<>();
        if (pinnedSpkiSha256 != null) {
            for (String p : pinnedSpkiSha256) {
                if (p == null || p.isBlank()) {
                    continue;
                }
                pins.add(HEX.parseHex(p.toLowerCase().replace(":", "").trim()));
            }
        }
        if (pins.isEmpty()) {
            throw new IllegalStateException(
                    channel + " channel has issuer certs but no pinned SPKI SHA-256 (fail-closed)");
        }
        List<PinnedIssuer> out = new ArrayList<>();
        for (X509Certificate issuer : channelIssuers) {
            byte[] spki = spkiSha256(issuer.getPublicKey());
            if (!containsConstantTime(pins, spki)) {
                throw new IllegalStateException(
                        channel + " issuer SPKI SHA-256 " + HEX.formatHex(spki)
                                + " is not in the pinned set");
            }
            out.add(new PinnedIssuer(channel, issuer.getPublicKey()));
        }
        return out;
    }

    /**
     * Resolve the channel for {@code leaf} and return the parsed identity.
     *
     * @throws MachineCertExtractionException if the leaf is untrusted, ambiguous,
     *         cross-contaminated, or fails the delegated channel extractor
     */
    public ChannelResolution resolve(X509Certificate leaf, Instant now) {
        Objects.requireNonNull(leaf, "leaf");
        Objects.requireNonNull(now, "now");

        // 1. Channel = the channel of the pinned issuer whose key verifies the leaf signature.
        Channel matched = null;
        for (PinnedIssuer issuer : pinnedIssuers) {
            if (verifies(leaf, issuer.publicKey())) {
                if (matched == null) {
                    matched = issuer.channel();
                } else if (matched != issuer.channel()) {
                    throw new MachineCertExtractionException(
                            "CHANNEL_ISSUER_AMBIGUOUS",
                            "Leaf verifies against pinned issuers from more than one channel.");
                }
            }
        }
        if (matched == null) {
            throw new MachineCertExtractionException(
                    "CHANNEL_ISSUER_UNTRUSTED",
                    "Leaf does not verify against any pinned enrollment-channel issuer.");
        }

        // 2. Cross-channel SAN contamination guard (the cert must not carry the OTHER channel's SAN).
        assertNoForeignSan(leaf, matched);

        // 3. Delegate to the channel extractor (MachineCertExtractor is UNCHANGED).
        return switch (matched) {
            case AD_CS -> new ChannelResolution.AdCs(MachineCertExtractor.extract(leaf, now));
            case VAULT_TPM -> new ChannelResolution.VaultTpm(TpmVaultCertExtractor.extract(leaf, now));
        };
    }

    private static void assertNoForeignSan(X509Certificate leaf, Channel channel) {
        java.util.regex.Pattern foreign = channel == Channel.AD_CS
                ? TpmVaultCertExtractor.SAN_URI_PATTERN      // AD CS cert must NOT carry a tpm: SAN
                : MachineCertExtractor.SAN_URI_PATTERN;      // Vault cert must NOT carry an adcomputer: SAN
        Collection<List<?>> sans;
        try {
            sans = leaf.getSubjectAlternativeNames();
        } catch (CertificateParsingException ex) {
            throw new MachineCertExtractionException(
                    "CHANNEL_SAN_PARSE_FAILED",
                    "Failed to parse cert SAN extension for cross-channel check.",
                    ex);
        }
        if (sans == null) {
            return;
        }
        for (List<?> entry : sans) {
            if (entry.size() < 2) {
                continue;
            }
            if (!(entry.get(0) instanceof Integer type) || type != SAN_TYPE_URI) {
                continue;
            }
            if (entry.get(1) instanceof String uri && foreign.matcher(uri).matches()) {
                throw new MachineCertExtractionException(
                        "CHANNEL_SAN_CROSS_CONTAMINATION",
                        "Cert resolved to channel " + channel
                                + " but carries a SAN URI of the other channel.");
            }
        }
    }

    private static boolean verifies(X509Certificate leaf, PublicKey issuerKey) {
        try {
            leaf.verify(issuerKey);
            return true;
        } catch (Exception ex) {
            // SignatureException / InvalidKeyException / NoSuchAlgorithmException / etc.
            // => this issuer did not sign the leaf.
            return false;
        }
    }

    private static byte[] spkiSha256(PublicKey key) {
        try {
            // PublicKey.getEncoded() yields the X.509 SubjectPublicKeyInfo DER.
            return MessageDigest.getInstance("SHA-256").digest(key.getEncoded());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    private static boolean spkiEquals(PublicKey a, PublicKey b) {
        return MessageDigest.isEqual(spkiSha256(a), spkiSha256(b));
    }

    /** Constant-time membership: compares against every pin without short-circuiting. */
    private static boolean containsConstantTime(List<byte[]> pins, byte[] candidate) {
        boolean found = false;
        for (byte[] pin : pins) {
            found |= MessageDigest.isEqual(pin, candidate);
        }
        return found;
    }
}

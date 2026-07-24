package com.example.ethics.evidence;

import com.example.ethics.config.EvidenceProperties;
import com.example.ethics.model.EvidenceAttachment;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

@Component
public class EvidenceManifestSigner {
    private static final HexFormat HEX = HexFormat.of();
    private final EvidenceProperties properties;

    public EvidenceManifestSigner(EvidenceProperties properties) {
        this.properties = properties;
    }

    public SignedManifest sign(
            EvidenceAttachment attachment,
            EvidenceProcessor.ProcessedEvidence processed,
            String derivativeSha256,
            long derivativeSize,
            String previousHash,
            int version,
            Instant createdAt) {
        String canonical = field(attachment.getId().toString())
                + field(Integer.toString(version))
                + field(attachment.getSealedSha256())
                + field(Long.toString(attachment.getSealedSize()))
                + field(derivativeSha256)
                + field(Long.toString(derivativeSize))
                + field(processed.inputMediaType())
                + field(processed.outputMediaType())
                + field(processed.scannerDigest())
                + field(processed.sanitizerDigest())
                + field(processed.parserDigest())
                + field(processed.rulesVersion())
                + field(attachment.getPolicyVersion())
                + field(processed.transformationProfile())
                + field(previousHash == null ? "" : previousHash)
                + field(createdAt.toString());
        byte[] canonicalBytes = canonical.getBytes(StandardCharsets.UTF_8);
        try {
            String manifestHash = HEX.formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
            Mac hmac = Mac.getInstance("HmacSHA256");
            hmac.init(new SecretKeySpec(
                    properties.getManifestSigningKey().getBytes(StandardCharsets.UTF_8),
                    "HmacSHA256"));
            String signature = HEX.formatHex(hmac.doFinal(canonicalBytes));
            return new SignedManifest(manifestHash, signature);
        } catch (Exception error) {
            throw new IllegalStateException("Evidence manifest could not be signed", error);
        }
    }

    private static String field(String value) {
        String safe = value == null ? "" : value;
        return safe.length() + ":" + safe;
    }

    public record SignedManifest(String hash, String signature) {}
}

package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ethics_evidence_derivations")
public class EvidenceDerivation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long seq;
    @Column(nullable = false, unique = true, updatable = false) private UUID id;
    @Column(name = "attachment_id", nullable = false, updatable = false) private UUID attachmentId;
    @Column(name = "derivation_version", nullable = false, updatable = false) private int derivationVersion;
    @Column(name = "sealed_sha256", nullable = false, updatable = false, length = 64) private String sealedSha256;
    @Column(name = "sealed_size", nullable = false, updatable = false) private long sealedSize;
    @Column(name = "derivative_sha256", nullable = false, updatable = false, length = 64) private String derivativeSha256;
    @Column(name = "derivative_size", nullable = false, updatable = false) private long derivativeSize;
    @Column(name = "input_media_type", nullable = false, updatable = false, length = 80) private String inputMediaType;
    @Column(name = "output_media_type", nullable = false, updatable = false, length = 80) private String outputMediaType;
    @Column(name = "scanner_digest", nullable = false, updatable = false, length = 120) private String scannerDigest;
    @Column(name = "sanitizer_digest", nullable = false, updatable = false, length = 120) private String sanitizerDigest;
    @Column(name = "parser_digest", nullable = false, updatable = false, length = 120) private String parserDigest;
    @Column(name = "rules_version", nullable = false, updatable = false, length = 120) private String rulesVersion;
    @Column(name = "policy_version", nullable = false, updatable = false, length = 80) private String policyVersion;
    @Column(name = "transformation_profile", nullable = false, updatable = false, length = 120) private String transformationProfile;
    @Column(name = "previous_manifest_hash", updatable = false, length = 64) private String previousManifestHash;
    @Column(name = "manifest_hash", nullable = false, updatable = false, length = 64) private String manifestHash;
    @Column(name = "signature_alg", nullable = false, updatable = false, length = 40) private String signatureAlg;
    @Column(name = "manifest_signature", nullable = false, updatable = false, length = 128) private String manifestSignature;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;

    protected EvidenceDerivation() {}

    public EvidenceDerivation(
            UUID id,
            UUID attachmentId,
            int derivationVersion,
            String sealedSha256,
            long sealedSize,
            String derivativeSha256,
            long derivativeSize,
            String inputMediaType,
            String outputMediaType,
            String scannerDigest,
            String sanitizerDigest,
            String parserDigest,
            String rulesVersion,
            String policyVersion,
            String transformationProfile,
            String previousManifestHash,
            String manifestHash,
            String manifestSignature,
            Instant createdAt) {
        this.id = id;
        this.attachmentId = attachmentId;
        this.derivationVersion = derivationVersion;
        this.sealedSha256 = sealedSha256;
        this.sealedSize = sealedSize;
        this.derivativeSha256 = derivativeSha256;
        this.derivativeSize = derivativeSize;
        this.inputMediaType = inputMediaType;
        this.outputMediaType = outputMediaType;
        this.scannerDigest = scannerDigest;
        this.sanitizerDigest = sanitizerDigest;
        this.parserDigest = parserDigest;
        this.rulesVersion = rulesVersion;
        this.policyVersion = policyVersion;
        this.transformationProfile = transformationProfile;
        this.previousManifestHash = previousManifestHash;
        this.manifestHash = manifestHash;
        this.signatureAlg = "HMAC-SHA256";
        this.manifestSignature = manifestSignature;
        this.createdAt = createdAt;
    }

    public Long getSeq() { return seq; }
    public UUID getId() { return id; }
    public UUID getAttachmentId() { return attachmentId; }
    public int getDerivationVersion() { return derivationVersion; }
    public String getManifestHash() { return manifestHash; }
    public String getManifestSignature() { return manifestSignature; }
}

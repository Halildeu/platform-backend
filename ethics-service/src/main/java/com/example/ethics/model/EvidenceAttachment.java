package com.example.ethics.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ethics_evidence_attachments")
public class EvidenceAttachment {
    @Id private UUID id;
    @Column(name = "case_id", nullable = false, updatable = false) private UUID caseId;
    @Column(name = "org_id", nullable = false, updatable = false) private UUID orgId;
    @Column(nullable = false, updatable = false, length = 80) private String channel;
    @Column(nullable = false, length = 40) private String state;
    @Column(name = "idempotency_key", nullable = false, updatable = false, length = 200) private String idempotencyKey;
    @Column(name = "request_hash", nullable = false, updatable = false, length = 64) private String requestHash;
    @Column(name = "policy_version", nullable = false, updatable = false, length = 80) private String policyVersion;
    @Column(name = "declared_media_type", nullable = false, updatable = false, length = 80) private String declaredMediaType;
    @Column(name = "declared_size", nullable = false, updatable = false) private long declaredSize;
    @Column(name = "declared_sha256", nullable = false, updatable = false, length = 64) private String declaredSha256;
    @Column(name = "quarantine_key", nullable = false, updatable = false, length = 160) private String quarantineKey;
    @Column(name = "sealed_key", nullable = false, updatable = false, length = 160) private String sealedKey;
    @Column(name = "derivative_key", nullable = false, updatable = false, length = 160) private String derivativeKey;
    @Column(name = "upload_capability_hash", nullable = false, length = 64) private String uploadCapabilityHash;
    @Column(name = "upload_expires_at", nullable = false) private Instant uploadExpiresAt;
    @Column(name = "upload_consumed_at") private Instant uploadConsumedAt;
    @Column(name = "sealed_version_id", length = 240) private String sealedVersionId;
    @Column(name = "sealed_sha256", length = 64) private String sealedSha256;
    @Column(name = "sealed_size") private Long sealedSize;
    @Column(name = "derivative_version_id", length = 240) private String derivativeVersionId;
    @Column(name = "derivative_sha256", length = 64) private String derivativeSha256;
    @Column(name = "derivative_size") private Long derivativeSize;
    @Column(name = "derivative_media_type", length = 80) private String derivativeMediaType;
    @Column(name = "failure_code", length = 120) private String failureCode;
    @Column(name = "attempt_count", nullable = false) private int attemptCount;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "claim_token") private UUID claimToken;
    @Column(name = "locked_until") private Instant lockedUntil;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version private long version;

    protected EvidenceAttachment() {}

    public EvidenceAttachment(
            UUID id,
            UUID caseId,
            UUID orgId,
            String channel,
            String idempotencyKey,
            String requestHash,
            String policyVersion,
            String declaredMediaType,
            long declaredSize,
            String declaredSha256,
            String quarantineKey,
            String sealedKey,
            String derivativeKey,
            String uploadCapabilityHash,
            Instant uploadExpiresAt,
            Instant now) {
        this.id = id;
        this.caseId = caseId;
        this.orgId = orgId;
        this.channel = channel;
        this.state = "DECLARED";
        this.idempotencyKey = idempotencyKey;
        this.requestHash = requestHash;
        this.policyVersion = policyVersion;
        this.declaredMediaType = declaredMediaType;
        this.declaredSize = declaredSize;
        this.declaredSha256 = declaredSha256;
        this.quarantineKey = quarantineKey;
        this.sealedKey = sealedKey;
        this.derivativeKey = derivativeKey;
        this.uploadCapabilityHash = uploadCapabilityHash;
        this.uploadExpiresAt = uploadExpiresAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void startUploading(Instant now) {
        requireState("DECLARED");
        state = "UPLOADING";
        updatedAt = now;
    }

    public void rotateUploadCapability(String capabilityHash, Instant expiresAt, Instant now) {
        requireState("UPLOADING");
        if (uploadConsumedAt != null) {
            throw new IllegalStateException("Consumed upload capability cannot rotate");
        }
        uploadCapabilityHash = capabilityHash;
        uploadExpiresAt = expiresAt;
        updatedAt = now;
    }

    public UUID getId() { return id; }
    public UUID getCaseId() { return caseId; }
    public UUID getOrgId() { return orgId; }
    public String getChannel() { return channel; }
    public String getState() { return state; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public String getPolicyVersion() { return policyVersion; }
    public String getDeclaredMediaType() { return declaredMediaType; }
    public long getDeclaredSize() { return declaredSize; }
    public String getDeclaredSha256() { return declaredSha256; }
    public String getQuarantineKey() { return quarantineKey; }
    public String getSealedKey() { return sealedKey; }
    public String getDerivativeKey() { return derivativeKey; }
    public String getUploadCapabilityHash() { return uploadCapabilityHash; }
    public Instant getUploadExpiresAt() { return uploadExpiresAt; }
    public Instant getUploadConsumedAt() { return uploadConsumedAt; }
    public String getSealedVersionId() { return sealedVersionId; }
    public String getSealedSha256() { return sealedSha256; }
    public Long getSealedSize() { return sealedSize; }
    public String getDerivativeVersionId() { return derivativeVersionId; }
    public String getDerivativeSha256() { return derivativeSha256; }
    public Long getDerivativeSize() { return derivativeSize; }
    public String getDerivativeMediaType() { return derivativeMediaType; }
    public String getFailureCode() { return failureCode; }
    public int getAttemptCount() { return attemptCount; }
    public UUID getClaimToken() { return claimToken; }
    public Instant getLockedUntil() { return lockedUntil; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }

    public void markQuarantined(Instant now) {
        requireState("UPLOADING");
        state = "QUARANTINED";
        uploadConsumedAt = now;
        updatedAt = now;
    }

    public void markIntegrityVerified(Instant now) {
        requireState("QUARANTINED");
        state = "INTEGRITY_VERIFIED";
        updatedAt = now;
    }

    public void markOriginalSealed(String versionId, String sha256, long size, Instant now) {
        requireState("INTEGRITY_VERIFIED");
        state = "ORIGINAL_SEALED";
        sealedVersionId = versionId;
        sealedSha256 = sha256;
        sealedSize = size;
        failureCode = null;
        nextAttemptAt = now;
        updatedAt = now;
    }

    public void scheduleSealRetry(String code, Instant retryAt, Instant now) {
        requireState("INTEGRITY_VERIFIED");
        failureCode = code;
        nextAttemptAt = retryAt;
        updatedAt = now;
    }

    public void markScanning(UUID token, Instant lockedUntil, Instant now) {
        if (!("ORIGINAL_SEALED".equals(state) || "SCAN_PENDING".equals(state))) {
            throw new IllegalStateException("Evidence cannot enter scanning from " + state);
        }
        state = "SCANNING";
        claimToken = token;
        this.lockedUntil = lockedUntil;
        attemptCount++;
        failureCode = null;
        updatedAt = now;
    }

    public void markSanitizing(Instant now) {
        requireState("SCANNING");
        state = "SANITIZING";
        updatedAt = now;
    }

    public void markDerivativeReady(
            String versionId, String sha256, long size, String mediaType, Instant now) {
        requireState("SANITIZING");
        state = "DERIVATIVE_READY";
        derivativeVersionId = versionId;
        derivativeSha256 = sha256;
        derivativeSize = size;
        derivativeMediaType = mediaType;
        updatedAt = now;
    }

    public void markAvailable(Instant now) {
        requireState("DERIVATIVE_READY");
        state = "AVAILABLE";
        claimToken = null;
        lockedUntil = null;
        nextAttemptAt = null;
        failureCode = null;
        updatedAt = now;
    }

    public void markRejected(String targetState, String code, Instant now) {
        if (!java.util.Set.of(
                "REJECTED_INTEGRITY", "REJECTED_POLICY", "MALICIOUS_QUARANTINED",
                "SANITIZE_FAILED").contains(targetState)) {
            throw new IllegalArgumentException("Unsupported evidence rejection state");
        }
        state = targetState;
        failureCode = code;
        claimToken = null;
        lockedUntil = null;
        nextAttemptAt = null;
        updatedAt = now;
    }

    public void markScanPending(String code, Instant retryAt, Instant now) {
        state = "SCAN_PENDING";
        failureCode = code;
        claimToken = null;
        lockedUntil = null;
        nextAttemptAt = retryAt;
        updatedAt = now;
    }

    public void expireUnbound(Instant now) {
        if (!"UPLOADING".equals(state) || uploadConsumedAt != null) {
            throw new IllegalStateException("Only an unbound upload can expire");
        }
        state = "EXPIRED_UNBOUND";
        failureCode = "UPLOAD_CAPABILITY_EXPIRED";
        updatedAt = now;
    }

    private void requireState(String expected) {
        if (!expected.equals(state)) {
            throw new IllegalStateException(
                    "Evidence state transition requires " + expected + " but was " + state);
        }
    }
}

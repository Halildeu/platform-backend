package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 22.6 #548 slice-1 step-4 (prerequisite, Codex {@code 019efada} decision A) — the persisted, V10-proven
 * enrollment TPM record. Written on a successful TPM {@code /attest} (after Vault issuance), it is what the
 * canonical device-key SESSION verifier ({@code DEVICE_KEY_ATTESTATION_REAL}, a later slice) matches a live
 * response against to establish the AK&harr;EK binding — which cannot rest on {@code device_key==leaf} +
 * {@code Certify} + {@code EK-root} alone.
 *
 * <p><b>Insert-once + revoke-update</b> (mirrors {@code endpoint_machine_certs}): the binding fields are set at
 * creation and never mutate; the only update is {@link #revoke} (sets {@code revokedAt} + {@code revokedReason}
 * together). Single ACTIVE row per {@code (tenantId, deviceId)} is enforced by the partial unique index
 * {@code uq_tpm_binding_active_device}.
 *
 * <p>{@code akName} is stored RAW (the TPM Name is the canonical compare input the verifier uses); the AK public
 * key, EK cert, and device-key SPKI are kept as SHA-256 hex digests (enough for the verifier match, no raw DER).
 */
@Entity
@Table(name = "endpoint_tpm_device_binding",
        indexes = {
                @Index(name = "idx_tpm_binding_tenant_device", columnList = "tenant_id,device_id")
        })
public class EndpointTpmDeviceBinding {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "endpoint_enrollment_id", nullable = false)
    private UUID endpointEnrollmentId;

    @Column(name = "ak_name", nullable = false)
    private byte[] akName;

    @Column(name = "ak_pub_sha256", nullable = false, length = 64)
    private String akPubSha256;

    @Column(name = "ek_cert_sha256", nullable = false, length = 64)
    private String ekCertSha256;

    @Column(name = "device_key_spki_sha256", nullable = false, length = 64)
    private String deviceKeySpkiSha256;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_reason", length = 64)
    private String revokedReason;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected EndpointTpmDeviceBinding() {
        // JPA
    }

    public EndpointTpmDeviceBinding(UUID tenantId, UUID deviceId, UUID endpointEnrollmentId, byte[] akName,
                                    String akPubSha256, String ekCertSha256, String deviceKeySpkiSha256,
                                    Instant enrolledAt, Instant createdAt) {
        this.tenantId = tenantId;
        this.deviceId = deviceId;
        this.endpointEnrollmentId = endpointEnrollmentId;
        this.akName = akName == null ? null : akName.clone();
        this.akPubSha256 = akPubSha256;
        this.ekCertSha256 = ekCertSha256;
        this.deviceKeySpkiSha256 = deviceKeySpkiSha256;
        this.enrolledAt = enrolledAt;
        this.createdAt = createdAt;
    }

    /** Soft-revoke this binding (re-enrollment supersede or explicit revoke). Idempotent: a no-op if already revoked. */
    public void revoke(String reason, Instant at) {
        if (this.revokedAt != null) {
            return;
        }
        if (reason == null || reason.isBlank() || at == null) {
            throw new IllegalArgumentException("revoke requires a non-blank reason and an instant");
        }
        this.revokedAt = at;
        this.revokedReason = reason;
    }

    public boolean isActive() {
        return revokedAt == null;
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public UUID getEndpointEnrollmentId() {
        return endpointEnrollmentId;
    }

    public byte[] getAkName() {
        return akName == null ? null : akName.clone();
    }

    public String getAkPubSha256() {
        return akPubSha256;
    }

    public String getEkCertSha256() {
        return ekCertSha256;
    }

    public String getDeviceKeySpkiSha256() {
        return deviceKeySpkiSha256;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public Instant getRevokedAt() {
        return revokedAt;
    }

    public String getRevokedReason() {
        return revokedReason;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

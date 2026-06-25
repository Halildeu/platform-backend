package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Faz 22.6 #548 Phase 1.5 — the canonical TPM device-identity map (V76,
 * Codex 019eff93 P0-4). Maps a tenant-scoped server-derived TPM EK public-key
 * digest to its {@link EndpointDevice}. This is the SOLE adoption authority for
 * the TPM-native channel — never the agent-supplied {@code machine_fingerprint}
 * (see {@code TpmDeviceCompletionService}). Insert-once; never updated.
 */
@Entity
@Table(name = "endpoint_tpm_device_identity",
        uniqueConstraints = @UniqueConstraint(name = "uq_tpm_device_identity_tenant_ek",
                columnNames = {"tenant_id", "ek_pub_sha256"}),
        indexes = @Index(name = "idx_tpm_device_identity_device", columnList = "device_id"))
public class EndpointTpmDeviceIdentity {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    /** Lowercase 64-hex SHA-256 of the TPM EK public key (server-derived from the V2-validated EK). */
    @Column(name = "ek_pub_sha256", nullable = false, length = 64)
    private String ekPubSha256;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected EndpointTpmDeviceIdentity() {
        // JPA
    }

    public EndpointTpmDeviceIdentity(UUID tenantId, String ekPubSha256, UUID deviceId, Instant createdAt) {
        this.tenantId = tenantId;
        this.ekPubSha256 = ekPubSha256;
        this.deviceId = deviceId;
        this.createdAt = createdAt;
    }

    @PrePersist
    void prePersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getEkPubSha256() {
        return ekPubSha256;
    }

    public UUID getDeviceId() {
        return deviceId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof EndpointTpmDeviceIdentity that)) {
            return false;
        }
        return id != null && Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return id == null ? 0 : id.hashCode();
    }
}

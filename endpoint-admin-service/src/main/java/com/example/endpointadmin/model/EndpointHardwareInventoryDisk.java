package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinColumns;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-022 — per-disk facet of an {@link EndpointHardwareInventorySnapshot}.
 *
 * <p>Composite {@code (snapshot_id, tenant_id)} FK enforces tenant
 * integrity at the DB layer (Codex 019e7007 iter-4 absorb): a service
 * bug cannot persist a disk row under one tenant while its snapshot
 * lives under another. {@code ON DELETE CASCADE} on the snapshot means
 * a single DELETE on the parent removes the child disks atomically.
 */
@Entity
@Table(name = "endpoint_hardware_inventory_disks",
        indexes = @Index(
                name = "idx_endpoint_hardware_inventory_disks_snapshot",
                columnList = "snapshot_id,tenant_id"))
public class EndpointHardwareInventoryDisk {

    public enum MediaType {
        SSD, HDD, NVME, UNKNOWN
    }

    public enum BusType {
        SATA, NVME, USB, SCSI, IDE, UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "snapshot_id", referencedColumnName = "id",
                    nullable = false, updatable = false),
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id",
                    nullable = false, updatable = false,
                    insertable = false /* tenant column already inserted via dedicated field */)
    })
    private EndpointHardwareInventorySnapshot snapshot;

    /** Materialized tenant column kept for direct querying / partial
     * indexes — mirrored from {@code snapshot.tenantId} at persist time. */
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "device_path", columnDefinition = "text")
    private String devicePath;

    @Column(name = "model", columnDefinition = "text")
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(name = "media_type", length = 16)
    private MediaType mediaType;

    @Column(name = "capacity_bytes")
    private Long capacityBytes;

    @Column(name = "free_bytes")
    private Long freeBytes;

    @Enumerated(EnumType.STRING)
    @Column(name = "bus_type", length = 16)
    private BusType busType;

    @Column(name = "is_removable")
    private Boolean removable;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (snapshot != null && tenantId == null) {
            tenantId = snapshot.getTenantId();
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public EndpointHardwareInventorySnapshot getSnapshot() {
        return snapshot;
    }

    public void setSnapshot(EndpointHardwareInventorySnapshot snapshot) {
        this.snapshot = snapshot;
        if (snapshot != null) {
            this.tenantId = snapshot.getTenantId();
        }
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public String getDevicePath() {
        return devicePath;
    }

    public void setDevicePath(String devicePath) {
        this.devicePath = devicePath;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public MediaType getMediaType() {
        return mediaType;
    }

    public void setMediaType(MediaType mediaType) {
        this.mediaType = mediaType;
    }

    public Long getCapacityBytes() {
        return capacityBytes;
    }

    public void setCapacityBytes(Long capacityBytes) {
        this.capacityBytes = capacityBytes;
    }

    public Long getFreeBytes() {
        return freeBytes;
    }

    public void setFreeBytes(Long freeBytes) {
        this.freeBytes = freeBytes;
    }

    public BusType getBusType() {
        return busType;
    }

    public void setBusType(BusType busType) {
        this.busType = busType;
    }

    public Boolean getRemovable() {
        return removable;
    }

    public void setRemovable(Boolean removable) {
        this.removable = removable;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHardwareInventoryDisk that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

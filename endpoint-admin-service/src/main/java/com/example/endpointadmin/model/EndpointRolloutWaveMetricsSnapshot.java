package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * An append-only, provenance-bearing wave/fleet metrics snapshot written by the
 * deployment orchestrator (Faz 22.5 #527 §9.3). The stop-line evaluator reads the
 * LATEST snapshot per (org, rollout, wave) and computes §6's advisory
 * stop_line_status against it. Immutable once written (DB append-only trigger).
 */
@Entity
@Table(name = "endpoint_rollout_wave_metrics_snapshot")
public class EndpointRolloutWaveMetricsSnapshot {

    public static final String SOURCE_ORCHESTRATOR_SNAPSHOT = "orchestrator_snapshot";

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "rollout_id", nullable = false, updatable = false, length = 128)
    private String rolloutId;

    @Column(name = "wave_id", nullable = false, updatable = false, length = 128)
    private String waveId;

    @Column(name = "active_wave_size", nullable = false, updatable = false)
    private int activeWaveSize;

    @Column(name = "fleet_size", nullable = false, updatable = false)
    private int fleetSize;

    @Column(name = "stale_24h_count", nullable = false, updatable = false)
    private int stale24hCount;

    @Column(name = "source_type", nullable = false, updatable = false, length = 64)
    private String sourceType = SOURCE_ORCHESTRATOR_SNAPSHOT;

    @Column(name = "source_snapshot_id", updatable = false, length = 128)
    private String sourceSnapshotId;

    @Column(name = "captured_at", nullable = false, updatable = false)
    private Instant capturedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        if (orgId == null) {
            orgId = tenantId; // org_id == tenant_id canonical (Faz 21.1)
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (sourceType == null) {
            sourceType = SOURCE_ORCHESTRATOR_SNAPSHOT;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    public UUID getOrgId() {
        return orgId;
    }

    public String getRolloutId() {
        return rolloutId;
    }

    public void setRolloutId(String rolloutId) {
        this.rolloutId = rolloutId;
    }

    public String getWaveId() {
        return waveId;
    }

    public void setWaveId(String waveId) {
        this.waveId = waveId;
    }

    public int getActiveWaveSize() {
        return activeWaveSize;
    }

    public void setActiveWaveSize(int activeWaveSize) {
        this.activeWaveSize = activeWaveSize;
    }

    public int getFleetSize() {
        return fleetSize;
    }

    public void setFleetSize(int fleetSize) {
        this.fleetSize = fleetSize;
    }

    public int getStale24hCount() {
        return stale24hCount;
    }

    public void setStale24hCount(int stale24hCount) {
        this.stale24hCount = stale24hCount;
    }

    public String getSourceType() {
        return sourceType;
    }

    public String getSourceSnapshotId() {
        return sourceSnapshotId;
    }

    public void setSourceSnapshotId(String sourceSnapshotId) {
        this.sourceSnapshotId = sourceSnapshotId;
    }

    public Instant getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(Instant capturedAt) {
        this.capturedAt = capturedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}

package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.util.UUID;

/**
 * BE — child startup-app row for AG-040 startup exposure inventory.
 * Single-column @JoinColumn(snapshot_id) + scalar tenant_id mirrored at
 * @PrePersist; DB-layer composite FK (snapshot_id, tenant_id) →
 * snapshots(id, tenant_id) ON DELETE CASCADE.
 *
 * <p>Location enforcement: location MUST be one of the 10 bounded
 * anchors (HKLM/HKCU Run/RunOnce + WOW6432 + Common/User Startup folder
 * + 3 Task Scheduler buckets). DB CHECK + policy CHECK.
 *
 * <p>ProbeOrigin: REGISTRY vs SCHEDULED_TASK enum (DB CHECK + policy).
 *
 * <p>HARD REDACTION: name is the registry value name / scheduled-task
 * name / startup-folder basename WITHOUT extension. NEVER the full
 * executable path / command line / RunAs / working dir.
 */
@Entity
@Table(name = "endpoint_startup_exposure_apps")
public class EndpointStartupExposureApp {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "snapshot_id", nullable = false, updatable = false)
    private EndpointStartupExposureSnapshot snapshot;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "row_ordinal", nullable = false)
    private Integer rowOrdinal;

    @Column(name = "name", nullable = false, length = 256)
    private String name;

    @Column(name = "location", nullable = false, length = 48)
    private String location;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled;

    @Column(name = "probe_origin", nullable = false, length = 16)
    private String probeOrigin;

    @PrePersist
    void onPersist() {
        if (tenantId == null && snapshot != null) {
            tenantId = snapshot.getTenantId();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public EndpointStartupExposureSnapshot getSnapshot() { return snapshot; }
    public void setSnapshot(EndpointStartupExposureSnapshot snapshot) { this.snapshot = snapshot; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public Integer getRowOrdinal() { return rowOrdinal; }
    public void setRowOrdinal(Integer rowOrdinal) { this.rowOrdinal = rowOrdinal; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }
    public String getProbeOrigin() { return probeOrigin; }
    public void setProbeOrigin(String probeOrigin) { this.probeOrigin = probeOrigin; }
}

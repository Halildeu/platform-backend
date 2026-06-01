package com.example.endpointadmin.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * BE — append-only startup-apps + exposure-summary snapshot (Faz 22.5,
 * AG-040-be). Mirrors AG-039-be {@code EndpointServicesSnapshot}
 * composite-FK precedent + 3-table layout (snapshot + apps + probe_errors).
 * Extends snapshot root with 2 additional flat exposure scalars
 * (rdpEnabled, windowsFirewallEventLogEnabled) per Codex 019e8387 plan
 * iter-1 absorb.
 *
 * <h3>Wire shape mirror</h3>
 *
 * <p>Per-entry wire shape EXACTLY {@code {name, location, enabled,
 * probeOrigin}} — the {@code location} is an autorun ANCHOR enum (10
 * slots), NEVER a full executable path / command line / RunAs / working
 * dir. RDP active session count is NOT persisted (usage telemetry leak).
 *
 * <h3>Canonical-form payload hash</h3>
 *
 * <p>INCLUDES every persistable field: schemaVersion, supported,
 * probeComplete, rdpEnabled, windowsFirewallEventLogEnabled,
 * startupApps (full ordered list with all 4 fields), probeErrors
 * (ordered list with code + source + summary), probeDurationMs.
 * EXCLUDES: none. Each fresh observation appends a new snapshot.
 *
 * <h3>Idempotency</h3>
 *
 * <p>Dual UNIQUE: partial UNIQUE source_command_result_id + full UNIQUE
 * (tenant_id, device_id, payload_hash_sha256). Targetless ON CONFLICT
 * DO NOTHING catches both legs race-cleanly.
 */
@Entity
@Table(name = "endpoint_startup_exposure_snapshots",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_endpoint_se_snap_id_tnt",
                        columnNames = {"id", "tenant_id"}),
                @UniqueConstraint(
                        name = "uq_endpoint_se_snap_tnt_dev_hash",
                        columnNames = {"tenant_id", "device_id", "payload_hash_sha256"})
        },
        indexes = {
                @Index(name = "idx_endpoint_se_snap_tnt_dev_time",
                        columnList = "tenant_id,device_id,collected_at,created_at,id")
        })
public class EndpointStartupExposureSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    @Column(name = "source_command_result_id")
    private UUID sourceCommandResultId;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "supported", nullable = false)
    private Boolean supported;

    @Column(name = "probe_complete", nullable = false)
    private Boolean probeComplete;

    @Column(name = "rdp_enabled", nullable = false)
    private Boolean rdpEnabled;

    @Column(name = "windows_firewall_event_log_enabled", nullable = false)
    private Boolean windowsFirewallEventLogEnabled;

    @Column(name = "probe_duration_ms", nullable = false)
    private Integer probeDurationMs;

    @Column(name = "payload_hash_sha256", nullable = false, length = 64)
    private String payloadHashSha256;

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointStartupExposureApp> startupApps = new ArrayList<>();

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    @OrderBy("rowOrdinal ASC")
    private List<EndpointStartupExposureProbeError> probeErrors = new ArrayList<>();

    @PrePersist
    void onPersist() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public UUID getTenantId() { return tenantId; }
    public void setTenantId(UUID tenantId) { this.tenantId = tenantId; }
    public UUID getDeviceId() { return deviceId; }
    public void setDeviceId(UUID deviceId) { this.deviceId = deviceId; }
    public UUID getSourceCommandResultId() { return sourceCommandResultId; }
    public void setSourceCommandResultId(UUID sourceCommandResultId) { this.sourceCommandResultId = sourceCommandResultId; }
    public Integer getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(Integer schemaVersion) { this.schemaVersion = schemaVersion; }
    public Boolean getSupported() { return supported; }
    public void setSupported(Boolean supported) { this.supported = supported; }
    public Boolean getProbeComplete() { return probeComplete; }
    public void setProbeComplete(Boolean probeComplete) { this.probeComplete = probeComplete; }
    public Boolean getRdpEnabled() { return rdpEnabled; }
    public void setRdpEnabled(Boolean rdpEnabled) { this.rdpEnabled = rdpEnabled; }
    public Boolean getWindowsFirewallEventLogEnabled() { return windowsFirewallEventLogEnabled; }
    public void setWindowsFirewallEventLogEnabled(Boolean windowsFirewallEventLogEnabled) {
        this.windowsFirewallEventLogEnabled = windowsFirewallEventLogEnabled;
    }
    public Integer getProbeDurationMs() { return probeDurationMs; }
    public void setProbeDurationMs(Integer probeDurationMs) { this.probeDurationMs = probeDurationMs; }
    public String getPayloadHashSha256() { return payloadHashSha256; }
    public void setPayloadHashSha256(String payloadHashSha256) { this.payloadHashSha256 = payloadHashSha256; }
    public Instant getCollectedAt() { return collectedAt; }
    public void setCollectedAt(Instant collectedAt) { this.collectedAt = collectedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public List<EndpointStartupExposureApp> getStartupApps() { return startupApps; }
    public void setStartupApps(List<EndpointStartupExposureApp> startupApps) { this.startupApps = startupApps; }
    public List<EndpointStartupExposureProbeError> getProbeErrors() { return probeErrors; }
    public void setProbeErrors(List<EndpointStartupExposureProbeError> probeErrors) { this.probeErrors = probeErrors; }
}

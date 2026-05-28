package com.example.endpointadmin.model;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-022 — append-only hardware/system inventory snapshot (Faz 22.5).
 *
 * <p>One row per {@code COLLECT_INVENTORY} agent command-result that
 * carried a {@code details.inventory.hardware} block. Sensitive fields
 * (BIOS / disk serials, user paths, Windows SIDs, machine GUIDs,
 * tokens) are stripped or fail-closed rejected by the
 * {@code HardwareInventoryPayloadPolicy} pre-persist hook in
 * {@link com.example.endpointadmin.service.EndpointAgentCommandService}
 * before the command-result row is even saved — the raw agent payload
 * never lands in either {@code endpoint_command_results.result_payload}
 * or this snapshot's {@code redactedPayload}.
 *
 * <p>Composite-FK pattern (Codex 019e7007 iter-4 absorb): the
 * {@code (device_id, tenant_id)} FK to {@code endpoint_devices(id,
 * tenant_id)} physically forbids a cross-tenant misrouting (parity
 * with V12 install audit). The child tables — {@code endpoint_hardware_
 * inventory_disks} and {@code endpoint_hardware_inventory_network_
 * interfaces} — bind via {@code (snapshot_id, tenant_id)}, which means
 * a misrouting bug cannot store a disk under one tenant while its
 * snapshot lives under another.
 *
 * <p>Append-only history (Codex iter-2 P1 absorb): the snapshot table
 * has no UNIQUE on {@code (tenant_id, device_id)} — every successful
 * {@code COLLECT_INVENTORY} produces a new row so WEB-013 hardware
 * view's history accordion has something to render. {@code latest}
 * queries use {@code ORDER BY collected_at DESC, created_at DESC, id
 * DESC} with the matching composite index for index-only scans.
 *
 * <p>Idempotency: the partial UNIQUE on {@code source_command_result_
 * id} means the agent SUBMIT-result hook can safely re-deliver the
 * same command-result without producing a duplicate snapshot —
 * {@link com.example.endpointadmin.service.EndpointHardwareInventoryService}
 * catches {@code DataIntegrityViolationException} and returns the
 * existing row.
 *
 * <p>{@code source_command_result_id} FK uses {@code ON DELETE SET
 * NULL} so command-result retention cleanup does not cascade-delete
 * the hardware history (Codex iter-4 absorb).
 */
@Entity
@Table(name = "endpoint_hardware_inventory_snapshots",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_endpoint_hardware_inventory_snapshots_id_tenant",
                columnNames = {"id", "tenant_id"}),
        indexes = {
                @Index(name = "idx_endpoint_hardware_inventory_snapshots_tenant_device_time",
                        columnList = "tenant_id,device_id,collected_at,created_at,id")
        })
public class EndpointHardwareInventorySnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    /** Pointer to the originating agent command-result; NULL for
     * manual/test ingest paths. UNIQUE (partial) at the DB layer. */
    @Column(name = "source_command_result_id")
    private UUID sourceCommandResultId;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion;

    @Column(name = "supported", nullable = false)
    private Boolean supported;

    @Column(name = "cpu_model", columnDefinition = "text")
    private String cpuModel;

    @Column(name = "cpu_cores")
    private Short cpuCores;

    @Column(name = "cpu_frequency_mhz")
    private Integer cpuFrequencyMhz;

    @Column(name = "ram_total_bytes")
    private Long ramTotalBytes;

    @Column(name = "ram_available_bytes")
    private Long ramAvailableBytes;

    @Column(name = "os_name", columnDefinition = "text")
    private String osName;

    @Column(name = "os_version", columnDefinition = "text")
    private String osVersion;

    @Column(name = "os_kernel", columnDefinition = "text")
    private String osKernel;

    @Column(name = "os_arch", columnDefinition = "text")
    private String osArch;

    @Column(name = "bios_vendor", columnDefinition = "text")
    private String biosVendor;

    @Column(name = "bios_version", columnDefinition = "text")
    private String biosVersion;

    @Column(name = "manufacturer", columnDefinition = "text")
    private String manufacturer;

    @Column(name = "system_model", columnDefinition = "text")
    private String systemModel;

    @Column(name = "domain_joined")
    private Boolean domainJoined;

    @Column(name = "domain_name", columnDefinition = "text")
    private String domainName;

    @Column(name = "last_boot_at")
    private Instant lastBootAt;

    @Column(name = "payload_hash_sha256", nullable = false, length = 64, columnDefinition = "char(64)")
    private String payloadHashSha256;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "redacted_payload", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> redactedPayload = new HashMap<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "probe_errors", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> probeErrors = new ArrayList<>();

    @Column(name = "collected_at", nullable = false)
    private Instant collectedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    /** Child disks. CascadeType.ALL + orphanRemoval mirrors V12 install
     * audit + V8 software inventory child relations. */
    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    private List<EndpointHardwareInventoryDisk> disks = new ArrayList<>();

    @OneToMany(mappedBy = "snapshot", cascade = CascadeType.ALL,
            fetch = FetchType.LAZY, orphanRemoval = true)
    private List<EndpointHardwareInventoryNetworkInterface> networkInterfaces = new ArrayList<>();

    @PrePersist
    void onPersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
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

    public UUID getDeviceId() {
        return deviceId;
    }

    public void setDeviceId(UUID deviceId) {
        this.deviceId = deviceId;
    }

    public UUID getSourceCommandResultId() {
        return sourceCommandResultId;
    }

    public void setSourceCommandResultId(UUID sourceCommandResultId) {
        this.sourceCommandResultId = sourceCommandResultId;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public Boolean getSupported() {
        return supported;
    }

    public void setSupported(Boolean supported) {
        this.supported = supported;
    }

    public String getCpuModel() {
        return cpuModel;
    }

    public void setCpuModel(String cpuModel) {
        this.cpuModel = cpuModel;
    }

    public Short getCpuCores() {
        return cpuCores;
    }

    public void setCpuCores(Short cpuCores) {
        this.cpuCores = cpuCores;
    }

    public Integer getCpuFrequencyMhz() {
        return cpuFrequencyMhz;
    }

    public void setCpuFrequencyMhz(Integer cpuFrequencyMhz) {
        this.cpuFrequencyMhz = cpuFrequencyMhz;
    }

    public Long getRamTotalBytes() {
        return ramTotalBytes;
    }

    public void setRamTotalBytes(Long ramTotalBytes) {
        this.ramTotalBytes = ramTotalBytes;
    }

    public Long getRamAvailableBytes() {
        return ramAvailableBytes;
    }

    public void setRamAvailableBytes(Long ramAvailableBytes) {
        this.ramAvailableBytes = ramAvailableBytes;
    }

    public String getOsName() {
        return osName;
    }

    public void setOsName(String osName) {
        this.osName = osName;
    }

    public String getOsVersion() {
        return osVersion;
    }

    public void setOsVersion(String osVersion) {
        this.osVersion = osVersion;
    }

    public String getOsKernel() {
        return osKernel;
    }

    public void setOsKernel(String osKernel) {
        this.osKernel = osKernel;
    }

    public String getOsArch() {
        return osArch;
    }

    public void setOsArch(String osArch) {
        this.osArch = osArch;
    }

    public String getBiosVendor() {
        return biosVendor;
    }

    public void setBiosVendor(String biosVendor) {
        this.biosVendor = biosVendor;
    }

    public String getBiosVersion() {
        return biosVersion;
    }

    public void setBiosVersion(String biosVersion) {
        this.biosVersion = biosVersion;
    }

    public String getManufacturer() {
        return manufacturer;
    }

    public void setManufacturer(String manufacturer) {
        this.manufacturer = manufacturer;
    }

    public String getSystemModel() {
        return systemModel;
    }

    public void setSystemModel(String systemModel) {
        this.systemModel = systemModel;
    }

    public Boolean getDomainJoined() {
        return domainJoined;
    }

    public void setDomainJoined(Boolean domainJoined) {
        this.domainJoined = domainJoined;
    }

    public String getDomainName() {
        return domainName;
    }

    public void setDomainName(String domainName) {
        this.domainName = domainName;
    }

    public Instant getLastBootAt() {
        return lastBootAt;
    }

    public void setLastBootAt(Instant lastBootAt) {
        this.lastBootAt = lastBootAt;
    }

    public String getPayloadHashSha256() {
        return payloadHashSha256;
    }

    public void setPayloadHashSha256(String payloadHashSha256) {
        this.payloadHashSha256 = payloadHashSha256;
    }

    public Map<String, Object> getRedactedPayload() {
        return redactedPayload;
    }

    public void setRedactedPayload(Map<String, Object> redactedPayload) {
        this.redactedPayload = redactedPayload == null ? new HashMap<>() : redactedPayload;
    }

    public List<Map<String, Object>> getProbeErrors() {
        return probeErrors;
    }

    public void setProbeErrors(List<Map<String, Object>> probeErrors) {
        this.probeErrors = probeErrors == null ? new ArrayList<>() : probeErrors;
    }

    public Instant getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(Instant collectedAt) {
        this.collectedAt = collectedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }

    public List<EndpointHardwareInventoryDisk> getDisks() {
        return disks;
    }

    public void setDisks(List<EndpointHardwareInventoryDisk> disks) {
        this.disks = disks == null ? new ArrayList<>() : disks;
    }

    public List<EndpointHardwareInventoryNetworkInterface> getNetworkInterfaces() {
        return networkInterfaces;
    }

    public void setNetworkInterfaces(List<EndpointHardwareInventoryNetworkInterface> networkInterfaces) {
        this.networkInterfaces = networkInterfaces == null ? new ArrayList<>() : networkInterfaces;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHardwareInventorySnapshot that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * BE-022 — per-NIC facet of an {@link EndpointHardwareInventorySnapshot}.
 *
 * <p>{@code ip_addresses} is a JSONB array of IP literals (IPv4 /
 * IPv6 / link-local / scoped) rather than a PostgreSQL {@code TEXT[]}:
 * Codex 019e7007 iter-4 AGREE — the repo-native JSONB list mapping
 * carries less Hibernate / DDL friction, and a future
 * IP-filter backlog can normalize to a child IP table without
 * changing the snapshot shape.
 */
@Entity
@Table(name = "endpoint_hardware_inventory_network_interfaces",
        indexes = @Index(
                name = "idx_endpoint_hardware_inventory_network_interfaces_snapshot",
                columnList = "snapshot_id,tenant_id"))
public class EndpointHardwareInventoryNetworkInterface {

    public enum InterfaceType {
        ETHERNET, WIFI, LOOPBACK, VIRTUAL, UNKNOWN
    }

    public enum LinkState {
        UP, DOWN, UNKNOWN
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumns({
            @JoinColumn(name = "snapshot_id", referencedColumnName = "id",
                    nullable = false, updatable = false),
            @JoinColumn(name = "tenant_id", referencedColumnName = "tenant_id",
                    nullable = false, updatable = false, insertable = false)
    })
    private EndpointHardwareInventorySnapshot snapshot;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "name", columnDefinition = "text")
    private String name;

    /** MAC stored lowercase canonical form (e.g. {@code aa:bb:cc:dd:ee:ff}). */
    @Column(name = "mac_address", length = 17)
    private String macAddress;

    @Enumerated(EnumType.STRING)
    @Column(name = "interface_type", length = 16)
    private InterfaceType interfaceType;

    @Enumerated(EnumType.STRING)
    @Column(name = "link_state", length = 16)
    private LinkState linkState;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "ip_addresses", nullable = false, columnDefinition = "jsonb")
    private List<String> ipAddresses = new ArrayList<>();

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

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getMacAddress() {
        return macAddress;
    }

    public void setMacAddress(String macAddress) {
        this.macAddress = macAddress;
    }

    public InterfaceType getInterfaceType() {
        return interfaceType;
    }

    public void setInterfaceType(InterfaceType interfaceType) {
        this.interfaceType = interfaceType;
    }

    public LinkState getLinkState() {
        return linkState;
    }

    public void setLinkState(LinkState linkState) {
        this.linkState = linkState;
    }

    public List<String> getIpAddresses() {
        return ipAddresses;
    }

    public void setIpAddresses(List<String> ipAddresses) {
        this.ipAddresses = ipAddresses == null ? new ArrayList<>() : ipAddresses;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof EndpointHardwareInventoryNetworkInterface that)) return false;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}

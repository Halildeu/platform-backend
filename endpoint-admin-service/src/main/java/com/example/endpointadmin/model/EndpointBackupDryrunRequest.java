package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Faz 22.8A.3b (#648) — JPA entity for the backup dry-run issuing request
 * state machine (propose→approve dual-control). Consumes the 22.8A.3a registry
 * by OPAQUE {@code rootRef}; this entity carries NO raw path — {@code
 * rootsSnapshot} is path-free [{rootRef, rootVersion}] and the raw local_path
 * lives only in the registry + the (redacted) dispatch command payload.
 *
 * <p>org_id is DB-level (V70 trigger fills org_id=tenant_id; reads tenant-keyed)
 * and intentionally NOT mapped here — mirrors the Faz 21.1 endpoint-admin
 * convention.
 */
@Entity
@Table(name = "endpoint_backup_dryrun_requests",
        indexes = {
                @Index(name = "ix_bdr_req_device_created",
                        columnList = "tenant_id,device_id,created_at DESC")
        })
public class EndpointBackupDryrunRequest {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "device_id", nullable = false)
    private UUID deviceId;

    /** Set on approve — the dispatched COLLECT_BACKUP_DRYRUN command. */
    @Column(name = "command_id")
    private UUID commandId;

    @Column(name = "idempotency_key", nullable = false, length = 255)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "state", nullable = false, length = 32)
    private BackupDryrunRequestState state;

    @Column(name = "allowlist_profile_id", nullable = false, length = 255)
    private String allowlistProfileId;

    @Column(name = "byod", nullable = false)
    private boolean byod = false;

    @Column(name = "reason", length = 512)
    private String reason;

    /** Path-free drift snapshot: [{rootRef, rootVersion}] at propose time. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "roots_snapshot", nullable = false)
    private List<BackupDryrunRootSnapshot> rootsSnapshot = new ArrayList<>();

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    @Column(name = "approved_by", length = 255)
    private String approvedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "state_updated_at", nullable = false)
    private Instant stateUpdatedAt;

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

    public UUID getCommandId() {
        return commandId;
    }

    public void setCommandId(UUID commandId) {
        this.commandId = commandId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public BackupDryrunRequestState getState() {
        return state;
    }

    public void setState(BackupDryrunRequestState state) {
        this.state = state;
    }

    public String getAllowlistProfileId() {
        return allowlistProfileId;
    }

    public void setAllowlistProfileId(String allowlistProfileId) {
        this.allowlistProfileId = allowlistProfileId;
    }

    public boolean isByod() {
        return byod;
    }

    public void setByod(boolean byod) {
        this.byod = byod;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public List<BackupDryrunRootSnapshot> getRootsSnapshot() {
        return rootsSnapshot;
    }

    public void setRootsSnapshot(List<BackupDryrunRootSnapshot> rootsSnapshot) {
        this.rootsSnapshot = rootsSnapshot;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getStateUpdatedAt() {
        return stateUpdatedAt;
    }

    public void setStateUpdatedAt(Instant stateUpdatedAt) {
        this.stateUpdatedAt = stateUpdatedAt;
    }
}

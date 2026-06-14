package com.example.endpointadmin.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

/**
 * Faz 22.8A.3a (#648) — managed-data-root registry entry for the backup
 * dry-run issuing surface (contract §4; Codex 019ec45e "registry-first").
 *
 * <p>The issuing surface (22.8A.3b) references a root by its OPAQUE
 * {@code rootRef} only; this entity is the ONLY place the raw {@code localPath}
 * lives. Privacy boundary: {@code localPath} is internal-only — it is never
 * returned in a path-free response DTO, never written to an audit event, and is
 * resolved only into the dispatch command payload. {@code rootVersion} backs
 * the propose→approve drift snapshot (the approved scope must equal the
 * dispatched scope).
 */
@Entity
@Table(name = "endpoint_backup_dryrun_managed_roots",
        indexes = {
                @Index(name = "ix_bdr_managed_roots_tenant_enabled",
                        columnList = "tenant_id,enabled")
        })
public class EndpointBackupDryrunManagedRoot {

    @Id
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "root_ref", nullable = false, length = 255)
    private String rootRef;

    @Column(name = "path_class", nullable = false, length = 64)
    private String pathClass;

    /** INTERNAL-ONLY raw managed-root path — never surfaced in a path-free DTO/audit. */
    @Column(name = "local_path", nullable = false)
    private String localPath;

    @Column(name = "company_managed", nullable = false)
    private boolean companyManaged = true;

    @Column(name = "enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "root_version", nullable = false)
    private int rootVersion = 1;

    @Column(name = "created_by", nullable = false, length = 255)
    private String createdBy;

    /** Actor of the most recent mutation (register or enable/disable) — path-free trail. */
    @Column(name = "updated_by", nullable = false, length = 255)
    private String updatedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
        if (rootVersion < 1) {
            rootVersion = 1;
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

    public String getRootRef() {
        return rootRef;
    }

    public void setRootRef(String rootRef) {
        this.rootRef = rootRef;
    }

    public String getPathClass() {
        return pathClass;
    }

    public void setPathClass(String pathClass) {
        this.pathClass = pathClass;
    }

    public String getLocalPath() {
        return localPath;
    }

    public void setLocalPath(String localPath) {
        this.localPath = localPath;
    }

    public boolean isCompanyManaged() {
        return companyManaged;
    }

    public void setCompanyManaged(boolean companyManaged) {
        this.companyManaged = companyManaged;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getRootVersion() {
        return rootVersion;
    }

    public void setRootVersion(int rootVersion) {
        this.rootVersion = rootVersion;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}

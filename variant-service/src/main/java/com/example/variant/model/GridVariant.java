package com.example.variant.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "user_grid_variants",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "grid_id", "name"})
        }
)
public class GridVariant {

    @Id
    @Column(columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "grid_id", nullable = false, length = 128)
    private String gridId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "is_global", nullable = false)
    private boolean isGlobal = false;

    @Column(name = "is_global_default", nullable = false)
    private boolean isGlobalDefault = false;

    @Column(name = "state_json", nullable = false, columnDefinition = "text")
    private String stateJson;

    @Column(name = "schema_version", nullable = false)
    private Integer schemaVersion = 1;

    @Column(name = "is_compatible", nullable = false)
    private boolean isCompatible = true;

    @Column(name = "sort_order", nullable = false)
    private Integer sortOrder = 0;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (schemaVersion == null) {
            schemaVersion = 1;
        }
        if (!isGlobal) {
            isGlobalDefault = false;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
        if (!isGlobal) {
            isGlobalDefault = false;
        }
        if (sortOrder == null) {
            sortOrder = 0;
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getGridId() {
        return gridId;
    }

    public void setGridId(String gridId) {
        this.gridId = gridId;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public boolean isGlobal() {
        return isGlobal;
    }

    public void setGlobal(boolean global) {
        isGlobal = global;
    }

    public boolean isGlobalDefault() {
        return isGlobalDefault;
    }

    public void setGlobalDefault(boolean globalDefault) {
        isGlobalDefault = globalDefault;
    }

    public String getStateJson() {
        return stateJson;
    }

    public void setStateJson(String stateJson) {
        this.stateJson = stateJson;
    }

    public Integer getSchemaVersion() {
        return schemaVersion;
    }

    public void setSchemaVersion(Integer schemaVersion) {
        this.schemaVersion = schemaVersion;
    }

    public boolean isCompatible() {
        return isCompatible;
    }

    public void setCompatible(boolean compatible) {
        isCompatible = compatible;
    }

    public Integer getSortOrder() {
        return sortOrder;
    }

    public void setSortOrder(Integer sortOrder) {
        this.sortOrder = sortOrder;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}

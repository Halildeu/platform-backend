package com.example.variant.theme.domain;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "themes")
public class Theme {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ThemeType type;

    @Column(name = "base_theme_id")
    private UUID baseThemeId;

    @Column(nullable = false, length = 64)
    private String appearance;

    @Column(name = "surface_tone", length = 64)
    private String surfaceTone;

    @Embedded
    private ThemeAxes axes = new ThemeAxes();

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "theme_overrides", joinColumns = @JoinColumn(name = "theme_id"))
    @MapKeyColumn(name = "registry_key")
    @Column(name = "\"value\"")
    private Map<String, String> overrides = new HashMap<>();

    @Column(name = "is_global")
    private boolean global;

    @Column(name = "owner_user_id")
    private String ownerUserId;

    @Column(name = "version")
    private String version;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "active_flag")
    private Boolean activeFlag;

    @Column(name = "visibility")
    private String visibility;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    private void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
    }

    @PreUpdate
    private void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public ThemeType getType() {
        return type;
    }

    public void setType(ThemeType type) {
        this.type = type;
    }

    public UUID getBaseThemeId() {
        return baseThemeId;
    }

    public void setBaseThemeId(UUID baseThemeId) {
        this.baseThemeId = baseThemeId;
    }

    public String getAppearance() {
        return appearance;
    }

    public void setAppearance(String appearance) {
        this.appearance = appearance;
    }

    public String getSurfaceTone() {
        return surfaceTone;
    }

    public void setSurfaceTone(String surfaceTone) {
        this.surfaceTone = surfaceTone;
    }

    public ThemeAxes getAxes() {
        return axes;
    }

    public void setAxes(ThemeAxes axes) {
        this.axes = axes;
    }

    public Map<String, String> getOverrides() {
        return overrides;
    }

    public void setOverrides(Map<String, String> overrides) {
        this.overrides = overrides != null ? new HashMap<>(overrides) : new HashMap<>();
    }

    public boolean isGlobal() {
        return global;
    }

    public void setGlobal(boolean global) {
        this.global = global;
    }

    public String getOwnerUserId() {
        return ownerUserId;
    }

    public void setOwnerUserId(String ownerUserId) {
        this.ownerUserId = ownerUserId;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public Boolean getActiveFlag() {
        return activeFlag;
    }

    public void setActiveFlag(Boolean activeFlag) {
        this.activeFlag = activeFlag;
    }

    public String getVisibility() {
        return visibility;
    }

    public void setVisibility(String visibility) {
        this.visibility = visibility;
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

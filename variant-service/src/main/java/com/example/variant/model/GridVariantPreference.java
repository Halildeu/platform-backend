package com.example.variant.model;

import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "user_grid_variant_preferences",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = {"user_id", "grid_id", "preference_type"})
        })
public class GridVariantPreference {

    @Id
    @Column(name = "id", nullable = false, columnDefinition = "uuid")
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "grid_id", nullable = false, length = 128)
    private String gridId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", columnDefinition = "uuid")
    private GridVariant variant;

    @Enumerated(EnumType.STRING)
    @Column(name = "preference_type", nullable = false, length = 64)
    private PreferenceType preferenceType;

    @Column(name = "is_default", nullable = false)
    private boolean isDefault = false;

    @Column(name = "is_selected", nullable = false)
    private boolean isSelected = false;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    public void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void onUpdate() {
        this.updatedAt = LocalDateTime.now();
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

    public GridVariant getVariant() {
        return variant;
    }

    public void setVariant(GridVariant variant) {
        this.variant = variant;
    }

    public PreferenceType getPreferenceType() {
        return preferenceType;
    }

    public void setPreferenceType(PreferenceType preferenceType) {
        this.preferenceType = preferenceType;
    }

    public boolean isDefault() {
        return isDefault;
    }

    public void setDefault(boolean aDefault) {
        isDefault = aDefault;
    }

    public boolean isSelected() {
        return isSelected;
    }

    public void setSelected(boolean selected) {
        isSelected = selected;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}

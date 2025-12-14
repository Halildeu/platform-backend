package com.example.variant.model;

import jakarta.persistence.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "variant_visibility")
public class VariantVisibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "variant_id", nullable = false)
    private GridVariant variant;

    @Enumerated(EnumType.STRING)
    @Column(name = "visibility_type", nullable = false, length = 32)
    private VisibilityType visibilityType;

    /**
     * COMPANY için companyId, USER için userId, GLOBAL için null, ROLE için role adı.
     */
    @Column(name = "ref_id", length = 64)
    private String refId;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt = OffsetDateTime.now();

    @Column(name = "created_by", length = 64)
    private String createdBy;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public GridVariant getVariant() {
        return variant;
    }

    public void setVariant(GridVariant variant) {
        this.variant = variant;
    }

    public VisibilityType getVisibilityType() {
        return visibilityType;
    }

    public void setVisibilityType(VisibilityType visibilityType) {
        this.visibilityType = visibilityType;
    }

    public String getRefId() {
        return refId;
    }

    public void setRefId(String refId) {
        this.refId = refId;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public enum VisibilityType {
        GLOBAL,
        COMPANY,
        USER,
        ROLE
    }
}

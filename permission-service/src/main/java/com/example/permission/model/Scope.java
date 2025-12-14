package com.example.permission.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "scopes", uniqueConstraints = @UniqueConstraint(name = "uk_scopes_type_ref", columnNames = {"scope_type", "ref_id"}))
public class Scope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "scope_type", nullable = false, length = 50)
    private String scopeType;

    @Column(name = "ref_id", nullable = false)
    private Long refId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_scope_id", foreignKey = @ForeignKey(name = "fk_scopes_parent"))
    private Scope parentScope;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getScopeType() {
        return scopeType;
    }

    public void setScopeType(String scopeType) {
        this.scopeType = scopeType;
    }

    public Long getRefId() {
        return refId;
    }

    public void setRefId(Long refId) {
        this.refId = refId;
    }

    public Scope getParentScope() {
        return parentScope;
    }

    public void setParentScope(Scope parentScope) {
        this.parentScope = parentScope;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
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

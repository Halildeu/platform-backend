package com.example.permission.model;

import jakarta.persistence.*;

import java.time.Instant;

@Entity
@Table(name = "user_permission_scope",
        uniqueConstraints = @UniqueConstraint(name = "uk_user_permission_scope", columnNames = {"user_id", "permission_id", "scope_id"}))
public class UserPermissionScope {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false, foreignKey = @ForeignKey(name = "fk_user_permission_scope_permission"))
    private Permission permission;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "scope_id", nullable = false, foreignKey = @ForeignKey(name = "fk_user_permission_scope_scope"))
    private Scope scope;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Permission getPermission() {
        return permission;
    }

    public void setPermission(Permission permission) {
        this.permission = permission;
    }

    public Scope getScope() {
        return scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }
}

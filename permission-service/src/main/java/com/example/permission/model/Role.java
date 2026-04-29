package com.example.permission.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "roles", uniqueConstraints = @UniqueConstraint(name = "uk_roles_name", columnNames = "name"))
public class Role {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at")
    private Instant updatedAt;

    @OneToMany(mappedBy = "role", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<RolePermission> rolePermissions = new HashSet<>();

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Set<RolePermission> getRolePermissions() {
        return rolePermissions;
    }

    public void setRolePermissions(Set<RolePermission> rolePermissions) {
        this.rolePermissions = rolePermissions;
    }

    /**
     * Codex 019dd818 iter-13 (Plan B): aggregate-native granule replace helper.
     *
     * <p>JPA bulk DELETE (e.g. {@code deleteByRoleId}) bypasses the persistence
     * context. If callers combine bulk delete with managed entity save, the
     * cascaded collection state can resurrect the deleted rows on
     * {@code save(role)} flush. {@code clearRolePermissions()} works through
     * the JPA aggregate so {@code orphanRemoval=true} drops the rows
     * deterministically.
     */
    public void clearRolePermissions() {
        rolePermissions.clear();
    }

    /**
     * Add a {@link RolePermission} to the aggregate, ensuring the back-reference
     * is set. Pairs with {@link #clearRolePermissions()} for the
     * "replace all granules" use case.
     */
    public void addRolePermission(RolePermission rolePermission) {
        rolePermission.setRole(this);
        rolePermissions.add(rolePermission);
    }
}

package com.example.permission.model;

import jakarta.persistence.*;

@Entity
@Table(name = "role_permissions",
        uniqueConstraints = @UniqueConstraint(name = "uk_role_permissions_role_permission", columnNames = {"role_id", "permission_id"}))
public class RolePermission {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "role_id", nullable = false, foreignKey = @ForeignKey(name = "fk_role_permissions_role"))
    private Role role;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "permission_id", nullable = false, foreignKey = @ForeignKey(name = "fk_role_permissions_permission"))
    private Permission permission;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Permission getPermission() {
        return permission;
    }

    public void setPermission(Permission permission) {
        this.permission = permission;
    }
}

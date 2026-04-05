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

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "permission_id", foreignKey = @ForeignKey(name = "fk_role_permissions_permission"))
    private Permission permission;

    @Column(name = "permission_type", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private PermissionType permissionType;

    @Column(name = "permission_key", nullable = false, length = 100)
    private String permissionKey;

    @Column(name = "grant_type", nullable = false, length = 10)
    @Enumerated(EnumType.STRING)
    private GrantType grantType;

    public RolePermission() {}

    public RolePermission(Role role, PermissionType permissionType, String permissionKey, GrantType grantType) {
        this.role = role;
        this.permissionType = permissionType;
        this.permissionKey = permissionKey;
        this.grantType = grantType;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }

    public Permission getPermission() { return permission; }
    public void setPermission(Permission permission) { this.permission = permission; }

    public PermissionType getPermissionType() { return permissionType; }
    public void setPermissionType(PermissionType permissionType) { this.permissionType = permissionType; }

    public String getPermissionKey() { return permissionKey; }
    public void setPermissionKey(String permissionKey) { this.permissionKey = permissionKey; }

    public GrantType getGrantType() { return grantType; }
    public void setGrantType(GrantType grantType) { this.grantType = grantType; }
}

package com.example.auth.user;

import java.time.LocalDateTime;

public class RemoteUserResponse {

    private Long id;
    private String name;
    private String email;
    private String role;
    private boolean enabled;
    private LocalDateTime createDate;
    private LocalDateTime lastLogin;

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

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public LocalDateTime getCreateDate() {
        return createDate;
    }

    public void setCreateDate(LocalDateTime createDate) {
        this.createDate = createDate;
    }

    public LocalDateTime getLastLogin() {
        return lastLogin;
    }

    public void setLastLogin(LocalDateTime lastLogin) {
        this.lastLogin = lastLogin;
    }

    /**
     * Keycloak subject (UUID) mapped to this platform user. Codex
     * {@code 019e1bed} AGREE — auth-service ImpersonationController
     * resolves target subject server-side from this field so the admin
     * UI never has to type a KC UUID. May be {@code null} for users
     * that pre-date the V16 user-service migration backfill; callers
     * must treat that as "subject unknown" and surface a remediation
     * error rather than passing {@code null} to KC token-exchange.
     */
    private String kcSubject;

    public String getKcSubject() {
        return kcSubject;
    }

    public void setKcSubject(String kcSubject) {
        this.kcSubject = kcSubject;
    }
}

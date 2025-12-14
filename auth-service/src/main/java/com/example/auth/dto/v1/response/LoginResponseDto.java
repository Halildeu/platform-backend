package com.example.auth.dto.v1.response;

import java.util.Set;

public class LoginResponseDto {
    private String token;
    private String email;
    private String role;
    private Set<String> permissions;
    private long expiresAt;
    private int sessionTimeoutMinutes;

    public LoginResponseDto() {
    }

    public LoginResponseDto(String token, String email, String role, Set<String> permissions, long expiresAt, int sessionTimeoutMinutes) {
        this.token = token;
        this.email = email;
        this.role = role;
        this.permissions = permissions;
        this.expiresAt = expiresAt;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
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

    public Set<String> getPermissions() {
        return permissions;
    }

    public void setPermissions(Set<String> permissions) {
        this.permissions = permissions;
    }

    public long getExpiresAt() {
        return expiresAt;
    }

    public void setExpiresAt(long expiresAt) {
        this.expiresAt = expiresAt;
    }

    public int getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(int sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }
}

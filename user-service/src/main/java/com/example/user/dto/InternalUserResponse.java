package com.example.user.dto;

/**
 * Internal representation of user credentials shared with trusted services.
 */
public class InternalUserResponse {
    private Long id;
    private String email;
    private String password;
    private String role;
    private boolean enabled;
    private Integer sessionTimeoutMinutes;

    public InternalUserResponse() {
    }

    public InternalUserResponse(Long id, String email, String password, String role, boolean enabled, Integer sessionTimeoutMinutes) {
        this.id = id;
        this.email = email;
        this.password = password;
        this.role = role;
        this.enabled = enabled;
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
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

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }
}

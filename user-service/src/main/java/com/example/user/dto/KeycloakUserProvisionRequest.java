package com.example.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Keycloak'tan gelen kullanıcı bilgilerini user-service veritabanına yansıtmak
 * için kullanılan DTO.
 */
public class KeycloakUserProvisionRequest {

    @NotBlank
    @Email
    private String email;

    @NotBlank
    @Size(min = 2, max = 256)
    private String name;

    @Size(min = 2, max = 64)
    private String role;

    private Boolean enabled;

    private Integer sessionTimeoutMinutes;

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }
}

package com.example.auth.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

// Bu anotasyon, JSON'dan nesneye çevrim sırasında bilinmeyen alanları görmezden gelir.
@JsonIgnoreProperties(ignoreUnknown = true) 
public class UserDto {
    private Long id;
    private String email;
    private String password;
    private String role;
    private boolean enabled;
    private Integer sessionTimeoutMinutes;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    // Getter ve Setter metotları
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

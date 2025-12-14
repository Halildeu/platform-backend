package com.example.user.dto.v1;

import java.time.LocalDateTime;

public class UserSummaryDto {
    private Long id;
    private String name;
    private String email;
    private String role;
    private boolean enabled;
    private LocalDateTime createDate;
    private LocalDateTime lastLogin;

    public UserSummaryDto() {
    }

    public UserSummaryDto(Long id, String name, String email, String role, boolean enabled, LocalDateTime createDate, LocalDateTime lastLogin) {
        this.id = id;
        this.name = name;
        this.email = email;
        this.role = role;
        this.enabled = enabled;
        this.createDate = createDate;
        this.lastLogin = lastLogin;
    }

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
}

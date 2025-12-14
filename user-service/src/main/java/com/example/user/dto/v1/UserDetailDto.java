package com.example.user.dto.v1;

import java.time.LocalDateTime;

public class UserDetailDto extends UserSummaryDto {

    private Integer sessionTimeoutMinutes;

    public UserDetailDto() {
        super();
    }

    public UserDetailDto(Long id,
                         String name,
                         String email,
                         String role,
                         boolean enabled,
                         LocalDateTime createDate,
                         LocalDateTime lastLogin,
                         Integer sessionTimeoutMinutes) {
        super(id, name, email, role, enabled, createDate, lastLogin);
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }
}

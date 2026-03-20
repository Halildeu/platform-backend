package com.example.user.dto.v1;

import java.time.LocalDateTime;

public class UserDetailDto extends UserSummaryDto {

    private Integer sessionTimeoutMinutes;
    private String locale;

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
                         Integer sessionTimeoutMinutes,
                         String locale) {
        super(id, name, email, role, enabled, createDate, lastLogin);
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
        this.locale = locale;
    }

    public Integer getSessionTimeoutMinutes() {
        return sessionTimeoutMinutes;
    }

    public void setSessionTimeoutMinutes(Integer sessionTimeoutMinutes) {
        this.sessionTimeoutMinutes = sessionTimeoutMinutes;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }
}

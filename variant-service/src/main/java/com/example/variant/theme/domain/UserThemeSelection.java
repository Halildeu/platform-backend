package com.example.variant.theme.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "user_theme_selections")
public class UserThemeSelection {

    @Id
    @Column(name = "user_id", nullable = false, length = 128)
    private String userId;

    @Column(name = "theme_id")
    private UUID themeId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public UUID getThemeId() {
        return themeId;
    }

    public void setThemeId(UUID themeId) {
        this.themeId = themeId;
    }
}


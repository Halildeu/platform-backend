package com.example.user.dto.v1;

import java.util.List;

public class NotificationPreferencesResponseDto {

    private List<NotificationPreferenceDto> preferences;

    public NotificationPreferencesResponseDto() {}

    public NotificationPreferencesResponseDto(List<NotificationPreferenceDto> preferences) {
        this.preferences = preferences;
    }

    public List<NotificationPreferenceDto> getPreferences() {
        return preferences;
    }

    public void setPreferences(List<NotificationPreferenceDto> preferences) {
        this.preferences = preferences;
    }
}


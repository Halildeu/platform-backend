package com.example.user.dto.v1;

import java.util.List;

public class UpdateNotificationPreferencesRequestDto {

    private List<NotificationPreferenceUpdateDto> preferences;

    public UpdateNotificationPreferencesRequestDto() {}

    public UpdateNotificationPreferencesRequestDto(List<NotificationPreferenceUpdateDto> preferences) {
        this.preferences = preferences;
    }

    public List<NotificationPreferenceUpdateDto> getPreferences() {
        return preferences;
    }

    public void setPreferences(List<NotificationPreferenceUpdateDto> preferences) {
        this.preferences = preferences;
    }
}


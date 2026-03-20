package com.example.user.dto.v1;

import java.time.LocalDateTime;

public class NotificationPreferenceDto {

    private String channel;
    private boolean enabled;
    private String frequency;
    private LocalDateTime updatedAt;
    private int version;

    public NotificationPreferenceDto() {}

    public NotificationPreferenceDto(String channel,
                                     boolean enabled,
                                     String frequency,
                                     LocalDateTime updatedAt,
                                     int version) {
        this.channel = channel;
        this.enabled = enabled;
        this.frequency = frequency;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}

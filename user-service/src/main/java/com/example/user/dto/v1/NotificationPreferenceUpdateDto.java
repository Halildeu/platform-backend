package com.example.user.dto.v1;

public class NotificationPreferenceUpdateDto {

    private String channel;
    private Boolean enabled;
    private String frequency;

    public NotificationPreferenceUpdateDto() {}

    public NotificationPreferenceUpdateDto(String channel, Boolean enabled, String frequency) {
        this.channel = channel;
        this.enabled = enabled;
        this.frequency = frequency;
    }

    public String getChannel() {
        return channel;
    }

    public void setChannel(String channel) {
        this.channel = channel;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getFrequency() {
        return frequency;
    }

    public void setFrequency(String frequency) {
        this.frequency = frequency;
    }
}


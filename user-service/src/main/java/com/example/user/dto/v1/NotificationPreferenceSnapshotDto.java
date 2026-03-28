package com.example.user.dto.v1;

public class NotificationPreferenceSnapshotDto {

    private String resourceKey;
    private String channel;
    private boolean enabled;
    private String frequency;
    private int version;

    public NotificationPreferenceSnapshotDto() {
    }

    public NotificationPreferenceSnapshotDto(String resourceKey,
                                             String channel,
                                             boolean enabled,
                                             String frequency,
                                             int version) {
        this.resourceKey = resourceKey;
        this.channel = channel;
        this.enabled = enabled;
        this.frequency = frequency;
        this.version = version;
    }

    public String getResourceKey() {
        return resourceKey;
    }

    public void setResourceKey(String resourceKey) {
        this.resourceKey = resourceKey;
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

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }
}

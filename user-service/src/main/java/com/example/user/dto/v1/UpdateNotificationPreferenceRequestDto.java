package com.example.user.dto.v1;

public class UpdateNotificationPreferenceRequestDto {

    private Boolean enabled;
    private String frequency;
    private Integer expectedVersion;
    private String source;
    private Integer attemptCount;
    private String queueActionId;

    public UpdateNotificationPreferenceRequestDto() {
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

    public Integer getExpectedVersion() {
        return expectedVersion;
    }

    public void setExpectedVersion(Integer expectedVersion) {
        this.expectedVersion = expectedVersion;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public Integer getAttemptCount() {
        return attemptCount;
    }

    public void setAttemptCount(Integer attemptCount) {
        this.attemptCount = attemptCount;
    }

    public String getQueueActionId() {
        return queueActionId;
    }

    public void setQueueActionId(String queueActionId) {
        this.queueActionId = queueActionId;
    }
}
